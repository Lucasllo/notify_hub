package dev.notifyhub.worker.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.worker.channel.ChannelAdapter;
import dev.notifyhub.worker.channel.SimulatedChannelAdapter;
import dev.notifyhub.worker.channel.SimulationProperties.ChannelSimulation;
import dev.notifyhub.worker.failure.FailureClassifier;
import dev.notifyhub.worker.listener.ChannelListeners;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher;
import dev.notifyhub.worker.metrics.DeliveryMetrics;
import dev.notifyhub.worker.resilience.ChannelGateway;
import dev.notifyhub.worker.resilience.ResilienceFixtures;
import dev.notifyhub.worker.template.TemplateCatalog;
import dev.notifyhub.worker.template.TemplateDocument;
import dev.notifyhub.worker.template.TemplateRenderer;
import dev.notifyhub.worker.template.TemplateSeeder;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * Caminho inteiro do worker sem broker nem banco: listener EMAIL → claim → template do seed →
 * renderização → adapter simulado → resultado numa única atualização com a tentativa registrada.
 * Na falha permanente, o resultado é FAILED e uma cópia confirmada vai para a DLQ do canal. Na
 * falha transitória, o resultado é RETRYING e um token confirmado desce para a fila de delay do
 * próximo nível (D-11).
 */
class DeliveryFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final TopologyProperties TOPOLOGY = new TopologyProperties(List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(25), Duration.ofSeconds(125)));

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private ChannelAdapter emailAdapter;
    private CircuitBreakerRegistry circuitBreakers;
    private BulkheadRegistry bulkheads;
    private SimpleMeterRegistry meters;
    private ChannelListeners listeners;

    @BeforeEach
    void wireRealWorker() {
        TemplateDocument welcome = new TemplateSeeder(mongo, new ObjectMapper()).loadSeed().stream()
                .filter(t -> t.id().equals("welcome"))
                .findFirst()
                .orElseThrow();
        when(mongo.findById("welcome", TemplateDocument.class)).thenReturn(welcome);

        claimReturns(processingDoc("welcome", 0));
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        // o broker confirma toda publicação
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(4);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        listeners = listenersWithEmailFailureRate(0.0);
    }

    /** Worker real com o adapter EMAIL falhando na taxa dada (1.0 = sempre transitória). */
    private ChannelListeners listenersWithEmailFailureRate(double emailFailureRate) {
        ChannelSimulation instant = new ChannelSimulation(Duration.ZERO, Duration.ZERO, 0.0);
        ChannelSimulation email = new ChannelSimulation(Duration.ZERO, Duration.ZERO, emailFailureRate);
        emailAdapter = spy(new SimulatedChannelAdapter(Channel.EMAIL, email, ThreadLocalRandom::current));
        List<ChannelAdapter> adapters = List.of(
                emailAdapter,
                new SimulatedChannelAdapter(Channel.SMS, instant, ThreadLocalRandom::current),
                new SimulatedChannelAdapter(Channel.WEBHOOK, instant, ThreadLocalRandom::current));

        // CB e bulkhead reais por canal, com a mesma configuração do application.yml
        // métricas reais sobre um registry em memória (D-15)
        meters = new SimpleMeterRegistry();
        DeliveryMetrics metrics = new DeliveryMetrics(meters, TOPOLOGY, new FailureClassifier());

        circuitBreakers = ResilienceFixtures.yamlCircuitBreakers();
        bulkheads = ResilienceFixtures.yamlBulkheads();
        ChannelGateway gateway = new ChannelGateway(adapters, circuitBreakers, bulkheads, TOPOLOGY, metrics);

        NotificationStateService state = new NotificationStateService(mongo, Duration.ofMinutes(5));
        DeliveryProcessor processor = new DeliveryProcessor(
                state, new TemplateCatalog(mongo), new TemplateRenderer(), gateway,
                new FailureClassifier(), new DeliveryRoutingPublisher(rabbit, Duration.ofSeconds(2)), TOPOLOGY,
                metrics);
        return new ChannelListeners(processor);
    }

    private static NotificationDocument processingDoc(String templateCode, int retryCount) {
        return processingDoc(templateCode, retryCount, 0);
    }

    private static NotificationDocument processingDoc(String templateCode, int retryCount, int attemptCount) {
        return processingDoc(templateCode, retryCount, attemptCount, "ana@exemplo.com");
    }

    private static NotificationDocument processingDoc(String templateCode, int retryCount, int attemptCount,
                                                      String recipient) {
        return new NotificationDocument(
                "n-1", Channel.EMAIL, recipient, templateCode, Map.of("name", "Ana"),
                NotificationStatus.PROCESSING, retryCount, attemptCount, null, null, List.of(),
                NOW, NOW, NOW, NOW.plus(Duration.ofMinutes(5)), null);
    }

    private void claimReturns(NotificationDocument doc) {
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(NotificationDocument.class)))
                .thenReturn(doc);
    }

    private record Result(Query query, UpdateDefinition update) {
    }

    private Result captureResult() {
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq(NotificationDocument.class));
        return new Result(query.getValue(), update.getValue());
    }

    /**
     * Entrega transitória com nível restante: exatamente dois {@code updateFirst}, o resultado
     * RETRYING e o avanço do fence depois do confirm (WR-01). Valida o avanço e devolve o resultado.
     */
    private Result captureRetryResult(int retryCount) {
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo, times(2)).updateFirst(query.capture(), update.capture(), eq(NotificationDocument.class));
        assertAdvance(new Result(query.getAllValues().get(1), update.getAllValues().get(1)), retryCount);
        return new Result(query.getAllValues().get(0), update.getAllValues().get(0));
    }

    /** Avanço do fence: RETRYING no nível h passa a h+1, só com retryCount e updatedAt. */
    private static void assertAdvance(Result advance, int retryCount) {
        assertThat(MongoBson.query(advance.query())).isEqualTo(new Document("_id", "n-1")
                .append("status", "RETRYING")
                .append("retryCount", retryCount));
        Document bson = MongoBson.update(advance.update());
        assertThat(bson.keySet()).containsExactly("$set");
        Document set = bson.get("$set", Document.class);
        assertThat(set.keySet()).containsExactlyInAnyOrder("retryCount", "updatedAt");
        assertThat(set.get("retryCount")).isEqualTo(retryCount + 1);
    }

    private record Published(Object body, MessagePostProcessor postProcessor, CorrelationData correlation) {
    }

    private Published captureDlqPublish(String routingKey) {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> cd = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit).convertAndSend(eq("notify.dlx"), eq(routingKey), body.capture(), mpp.capture(), cd.capture());
        return new Published(body.getValue(), mpp.getValue(), cd.getValue());
    }

    private double outcomeCount(String outcome, String failureClass) {
        return meters.get("notify.delivery.outcomes")
                .tags("channel", "email", "outcome", outcome, "failure_class", failureClass)
                .counter().count();
    }

    private static MessageProperties applied(MessagePostProcessor mpp) {
        return mpp.postProcessMessage(new Message(new byte[0], new MessageProperties())).getMessageProperties();
    }

    // ------------------------------------------------------------ caminho feliz

    @Test
    void emailMessageEndsSentInASingleAtomicUpdateWithTheAttemptRecorded() {
        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null);

        Document bson = MongoBson.update(captureResult().update());

        Document set = bson.get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("SENT");
        assertThat(set.get("rendered", Document.class).getString("subject")).contains("Ana");
        assertThat(bson.get("$inc", Document.class).get("attemptCount")).isEqualTo(1);

        Document push = bson.get("$push", Document.class).get("attempts", Document.class);
        assertThat(push.get("$slice")).isEqualTo(-50);
        List<?> each = push.get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("number")).isEqualTo(1);
        assertThat(attempt.get("retryLevel")).isEqualTo(0);
        assertThat(attempt.get("outcome")).isEqualTo("SUCCESS");
        assertThat(attempt.getString("thread")).isNotBlank();
    }

    // ------------------------------------------------------------ métricas (OBS-01, D-15)

    @Test
    void happyPathCountsOneSentEmailOutcome() {
        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null);

        assertThat(outcomeCount("sent", "none")).isEqualTo(1.0);
    }

    @Test
    void lostFenceOnSentCountsNothing() {
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null);

        assertThat(outcomeCount("sent", "none")).isZero();
    }

    @Test
    void claimFiltersByIdAndAbsentRetryHeaderBecomesLevelZero() {
        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(NotificationDocument.class));
        Document filter = MongoBson.query(query.getValue());

        assertThat(filter.get("_id")).isEqualTo("n-1");
        assertThat(filter.get("retryCount", Document.class).get("$lte")).isEqualTo(0);
    }

    @Test
    void redeliveredMessageReclaimsAStuckProcessingDocumentAndEndsSent() {
        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, true);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(NotificationDocument.class));
        Document filter = MongoBson.query(query.getValue());
        Document stuck = filter.getList("$or", Document.class).get(1);
        assertThat(stuck).isEqualTo(new Document("status", new Document("$in", List.of("PROCESSING"))));

        Document set = MongoBson.update(captureResult().update()).get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("SENT");
    }

    // ------------------------------------------------------------ falha permanente

    @Test
    void missingTemplateEndsWithoutExceptionAndNeverCallsTheAdapter() {
        claimReturns(processingDoc("nao-existe", 0));
        assertThat(mongo.findById("nao-existe", TemplateDocument.class)).isNull();

        assertThatCode(() -> listeners.onEmail(
                new NotificationMessage("n-1", Channel.EMAIL, "nao-existe", NOW), null, null))
                .doesNotThrowAnyException();

        verify(emailAdapter, never()).send(anyString(), any(), any());
    }

    @Test
    void missingTemplateIsWrittenAsFailedInOneFencedUpdateWithTheAttempt() {
        claimReturns(processingDoc("nao-existe", 0));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "nao-existe", NOW), null, null);

        Result result = captureResult();
        assertThat(MongoBson.query(result.query())).isEqualTo(new Document("_id", "n-1")
                .append("status", "PROCESSING")
                .append("retryCount", 0));

        Document bson = MongoBson.update(result.update());
        Document set = bson.get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("FAILED");
        Document lastError = set.get("lastError", Document.class);
        assertThat(lastError.getString("code")).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(lastError.get("failureClass")).isEqualTo("PERMANENT");
        assertThat(bson.get("$unset", Document.class).keySet()).contains("leaseUntil");
        assertThat(bson.get("$inc", Document.class).get("attemptCount")).isEqualTo(1);

        Document push = bson.get("$push", Document.class).get("attempts", Document.class);
        assertThat(push.get("$slice")).isEqualTo(-50);
        List<?> each = push.get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("number")).isEqualTo(1);
        assertThat(attempt.get("retryLevel")).isEqualTo(0);
        assertThat(attempt.get("outcome")).isEqualTo("PERMANENT_FAILURE");
        assertThat(attempt.get("errorCode")).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(attempt.get("failureClass")).isEqualTo("PERMANENT");
        assertThat(((Number) attempt.get("durationMs")).longValue()).isZero();
    }

    @Test
    void missingTemplateSendsAConfirmedCopyToTheChannelDlqWithTheFailureClass() {
        claimReturns(processingDoc("nao-existe", 0));
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "nao-existe", NOW);

        listeners.onEmail(message, null, null);

        Published published = captureDlqPublish("email.dlq");
        assertThat(published.body()).isEqualTo(message);
        MessageProperties props = applied(published.postProcessor());
        assertThat(props.getMessageId()).isEqualTo("n-1");
        assertThat((Object) props.getHeader("x-failure-class")).isEqualTo("PERMANENT");
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(0);
        assertThat(published.correlation().getId()).isNotEqualTo("n-1");
    }

    @Test
    void retryLevelFromTheHeaderIsRecordedInTheAttemptAndCarriedToTheDlqCopy() {
        claimReturns(processingDoc("nao-existe", 2));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "nao-existe", NOW), "2", null);

        Document bson = MongoBson.update(captureResult().update());
        Document attempt = (Document) bson.get("$push", Document.class)
                .get("attempts", Document.class).get("$each", List.class).get(0);
        assertThat(attempt.get("retryLevel")).isEqualTo(2);

        MessageProperties props = applied(captureDlqPublish("email.dlq").postProcessor());
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(2);
    }

    // ------------------------------------------------------------ falha transitória (D-11)

    private Published captureRetryPublish(String routingKey) {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> cd = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit).convertAndSend(eq("notify.retry"), eq(routingKey), body.capture(), mpp.capture(), cd.capture());
        return new Published(body.getValue(), mpp.getValue(), cd.getValue());
    }

    @Test
    void transientFailureAtLevelZeroIsWrittenAsRetryingWithoutTouchingRetryCount() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);

        failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null);

        Result result = captureRetryResult(0);
        assertThat(MongoBson.query(result.query())).isEqualTo(new Document("_id", "n-1")
                .append("status", "PROCESSING")
                .append("retryCount", 0));

        Document bson = MongoBson.update(result.update());
        Document set = bson.get("$set", Document.class);
        assertThat(set.keySet()).containsExactlyInAnyOrder("status", "lastError", "updatedAt");
        assertThat(set.get("status")).isEqualTo("RETRYING");
        Document lastError = set.get("lastError", Document.class);
        assertThat(lastError.getString("code")).isEqualTo("SIMULATED_TRANSIENT");
        assertThat(lastError.get("failureClass")).isEqualTo("TRANSIENT");
        assertThat(bson.get("$unset", Document.class).keySet()).containsExactly("leaseUntil");
        assertThat(bson.get("$inc", Document.class).get("attemptCount")).isEqualTo(1);

        List<?> each = bson.get("$push", Document.class).get("attempts", Document.class).get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("outcome")).isEqualTo("TRANSIENT_FAILURE");
        assertThat(attempt.get("errorCode")).isEqualTo("SIMULATED_TRANSIENT");
        assertThat(attempt.get("retryLevel")).isEqualTo(0);
    }

    @Test
    void transientFailurePublishesAConfirmedTokenToTheFirstDelayLevelAfterTheRetryingUpdate() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);

        assertThatCode(() -> failing.onEmail(message, null, null)).doesNotThrowAnyException();

        Published published = captureRetryPublish("email.retry.5s");
        assertThat(published.body()).isEqualTo(message);
        MessageProperties props = applied(published.postProcessor());
        assertThat(props.getMessageId()).isEqualTo("n-1");
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(1);
        assertThat(published.correlation().getId()).isNotEqualTo("n-1");

        InOrder order = inOrder(mongo, rabbit);
        order.verify(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        order.verify(rabbit).convertAndSend(eq("notify.retry"), eq("email.retry.5s"), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        // avanço do fence entre o confirm e o ack (WR-01)
        order.verify(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        Result result = captureRetryResult(0);
        assertThat(MongoBson.update(result.update()).get("$set", Document.class).get("status")).isEqualTo("RETRYING");
        verify(rabbit, never()).convertAndSend(eq("notify.dlx"), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void transientFailureAtLevelOneGoesToTheSecondDelayLevel() {
        claimReturns(processingDoc("welcome", 1));
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);

        failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), "1", null);

        MessageProperties props = applied(captureRetryPublish("email.retry.25s").postProcessor());
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(2);
    }

    // ------------------------------------------------------------ republicação falha (T-02-01, WR-05)

    /** Segundo {@code updateFirst}: RETRYING → DEAD_LETTERED com o lastError REPUBLISH_FAILED. */
    private Document republishFailureLastError() {
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo, times(2)).updateFirst(any(Query.class), update.capture(), eq(NotificationDocument.class));
        Document set = MongoBson.update(update.getAllValues().get(1)).get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("DEAD_LETTERED");
        return set.get("lastError", Document.class);
    }

    @Test
    void nackedRepublishRecordsOnlyTheEnumeratedReasonWithoutBrokerText() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        String brokerReason = "canal fechado ana@exemplo.com " + "x".repeat(1000);
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(4);
            boolean retry = "notify.retry".equals(invocation.getArgument(0));
            cd.getFuture().complete(retry
                    ? new CorrelationData.Confirm(false, brokerReason)
                    : new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        assertThatCode(() -> failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null))
                .doesNotThrowAnyException();

        Document lastError = republishFailureLastError();
        assertThat(lastError.getString("code")).isEqualTo("REPUBLISH_FAILED");
        assertThat(lastError.getString("message"))
                .isEqualTo("republicação para retry.5s falhou: NACK")
                .doesNotContain("ana@exemplo.com")
                .hasSizeLessThanOrEqualTo(300);
        captureDlqPublish("email.dlq");
    }

    @Test
    void republishSendFailureRecordsOnlyTheExceptionType() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        doThrow(new AmqpConnectException(new ConnectException("Connection refused ana@exemplo.com")))
                .when(rabbit).convertAndSend(eq("notify.retry"), anyString(), any(Object.class),
                        any(MessagePostProcessor.class), any(CorrelationData.class));

        assertThatCode(() -> failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null))
                .doesNotThrowAnyException();

        Document lastError = republishFailureLastError();
        assertThat(lastError.getString("code")).isEqualTo("REPUBLISH_FAILED");
        assertThat(lastError.getString("message"))
                .isEqualTo("republicação para retry.5s falhou: SEND_FAILED:AmqpConnectException")
                .doesNotContain("ana@exemplo.com");
        captureDlqPublish("email.dlq");
    }

    // ------------------------------------------------------------ escada esgotada (DLV-05)

    @Test
    void transientFailureOnTheLastLevelEndsDeadLetteredWithATransientDlqCopy() {
        claimReturns(processingDoc("welcome", 3, 3));
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);

        assertThatCode(() -> failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), "3", null))
                .doesNotThrowAnyException();

        Result result = captureResult();
        assertThat(MongoBson.query(result.query())).isEqualTo(new Document("_id", "n-1")
                .append("status", "PROCESSING")
                .append("retryCount", 3));
        Document set = MongoBson.update(result.update()).get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("DEAD_LETTERED");
        assertThat(set.get("lastError", Document.class).get("failureClass")).isEqualTo("TRANSIENT");

        MessageProperties props = applied(captureDlqPublish("email.dlq").postProcessor());
        assertThat((Object) props.getHeader("x-failure-class")).isEqualTo("TRANSIENT");
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(3);
        verify(rabbit, never()).convertAndSend(eq("notify.retry"), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void alwaysTransientWalksTheWholeLadderAndEndsDeadLetteredOnTheFourthAttempt() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);

        for (int h = 0; h <= 3; h++) {
            claimReturns(processingDoc("welcome", h, h));
            failing.onEmail(message, h == 0 ? null : String.valueOf(h), null);
        }

        // 4 resultados (com $push) e 3 avanços do fence (só $set de retryCount e updatedAt)
        ArgumentCaptor<UpdateDefinition> updates = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo, times(7)).updateFirst(any(Query.class), updates.capture(), eq(NotificationDocument.class));
        List<Document> all = updates.getAllValues().stream().map(MongoBson::update).toList();
        List<Document> bson = all.stream().filter(b -> b.containsKey("$push")).toList();
        List<Document> advances = all.stream().filter(b -> !b.containsKey("$push")).toList();
        assertThat(advances).extracting(b -> b.get("$set", Document.class).get("retryCount"))
                .containsExactly(1, 2, 3);
        assertThat(advances).allSatisfy(b -> assertThat(b.get("$set", Document.class).keySet())
                .containsExactlyInAnyOrder("retryCount", "updatedAt"));
        assertThat(bson).extracting(b -> b.get("$set", Document.class).get("status"))
                .containsExactly("RETRYING", "RETRYING", "RETRYING", "DEAD_LETTERED");
        assertThat(bson).extracting(b -> ((Document) b.get("$push", Document.class).get("attempts", Document.class)
                        .get("$each", List.class).get(0)).get("number"))
                .containsExactly(1, 2, 3, 4);

        InOrder order = inOrder(rabbit);
        for (String key : List.of("email.retry.5s", "email.retry.25s", "email.retry.125s")) {
            order.verify(rabbit).convertAndSend(eq("notify.retry"), eq(key), any(Object.class),
                    any(MessagePostProcessor.class), any(CorrelationData.class));
        }
        order.verify(rabbit).convertAndSend(eq("notify.dlx"), eq("email.dlq"), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void integerMaxValueRetryHeaderEndsDeadLetteredWithOneAttemptAndATransientDlqCopy() {
        // attemptCount vem do documento, não do header: 0 aqui, para não estourar o número da tentativa
        claimReturns(processingDoc("welcome", Integer.MAX_VALUE, 0));
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);

        assertThatCode(() -> failing.onEmail(
                new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), "2147483647", null))
                .doesNotThrowAnyException();

        Result result = captureResult();
        assertThat(MongoBson.query(result.query())).isEqualTo(new Document("_id", "n-1")
                .append("status", "PROCESSING")
                .append("retryCount", 2147483647));
        Document bson = MongoBson.update(result.update());
        Document set = bson.get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("DEAD_LETTERED");
        assertThat(set.get("lastError", Document.class).get("failureClass")).isEqualTo("TRANSIENT");
        assertThat(bson.get("$inc", Document.class).get("attemptCount")).isEqualTo(1);
        List<?> each = bson.get("$push", Document.class).get("attempts", Document.class).get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("number")).isEqualTo(1);
        assertThat(attempt.get("retryLevel")).isEqualTo(2147483647);
        assertThat(attempt.get("outcome")).isEqualTo("TRANSIENT_FAILURE");
        assertThat(attempt.get("errorCode")).isEqualTo("SIMULATED_TRANSIENT");

        MessageProperties props = applied(captureDlqPublish("email.dlq").postProcessor());
        assertThat((Object) props.getHeader("x-failure-class")).isEqualTo("TRANSIENT");
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(2147483647);
        verify(rabbit, never()).convertAndSend(eq("notify.retry"), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    // ------------------------------------------------------------ destinatários mágicos (DLV-02, D-02)

    private static final String FAIL_TWICE = "fail-2-then-ok@fail.test";

    @Test
    void failTwiceThenOkFailsTransientlyOnTheFirstDeliveryEvenWithFailureRateZero() {
        claimReturns(processingDoc("welcome", 0, 0, FAIL_TWICE));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null);

        Document bson = MongoBson.update(captureRetryResult(0).update());
        assertThat(bson.get("$set", Document.class).get("status")).isEqualTo("RETRYING");
        Document attempt = (Document) bson.get("$push", Document.class)
                .get("attempts", Document.class).get("$each", List.class).get(0);
        assertThat(attempt.get("errorCode")).isEqualTo("SIMULATED_TRANSIENT");
        assertThat(attempt.get("outcome")).isEqualTo("TRANSIENT_FAILURE");

        MessageProperties props = applied(captureRetryPublish("email.retry.5s").postProcessor());
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(1);
    }

    @Test
    void failTwiceThenOkFailsAgainOnTheFirstRetryLevel() {
        claimReturns(processingDoc("welcome", 1, 1, FAIL_TWICE));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), "1", null);

        Document set = MongoBson.update(captureRetryResult(1).update()).get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("RETRYING");
        MessageProperties props = applied(captureRetryPublish("email.retry.25s").postProcessor());
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(2);
    }

    @Test
    void failTwiceThenOkIsDeliveredOnTheThirdAttemptAtLevelTwo() {
        claimReturns(processingDoc("welcome", 2, 2, FAIL_TWICE));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), "2", null);

        Document bson = MongoBson.update(captureResult().update());
        assertThat(bson.get("$set", Document.class).get("status")).isEqualTo("SENT");
        Document attempt = (Document) bson.get("$push", Document.class)
                .get("attempts", Document.class).get("$each", List.class).get(0);
        assertThat(attempt.get("outcome")).isEqualTo("SUCCESS");
        assertThat(attempt.get("retryLevel")).isEqualTo(2);
        assertThat(attempt.get("number")).isEqualTo(3);
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void alwaysPermanentEndsFailedOnTheFirstAttemptWithAPermanentDlqCopy() {
        claimReturns(processingDoc("welcome", 0, 0, "always-permanent@fail.test"));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null);

        Document bson = MongoBson.update(captureResult().update());
        Document set = bson.get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("FAILED");
        assertThat(set.get("lastError", Document.class).getString("code")).isEqualTo("SIMULATED_PERMANENT");
        assertThat(bson.get("$inc", Document.class).get("attemptCount")).isEqualTo(1);
        List<?> each = bson.get("$push", Document.class).get("attempts", Document.class).get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("number")).isEqualTo(1);
        assertThat(attempt.get("errorCode")).isEqualTo("SIMULATED_PERMANENT");
        assertThat(attempt.get("failureClass")).isEqualTo("PERMANENT");

        MessageProperties props = applied(captureDlqPublish("email.dlq").postProcessor());
        assertThat((Object) props.getHeader("x-failure-class")).isEqualTo("PERMANENT");
        verify(rabbit, never()).convertAndSend(eq("notify.retry"), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void alwaysTransientOnTheLastLevelEndsDeadLetteredWithATransientDlqCopy() {
        claimReturns(processingDoc("welcome", 3, 3, "always-transient@fail.test"));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), "3", null);

        Document bson = MongoBson.update(captureResult().update());
        assertThat(bson.get("$set", Document.class).get("status")).isEqualTo("DEAD_LETTERED");
        Document attempt = (Document) bson.get("$push", Document.class)
                .get("attempts", Document.class).get("$each", List.class).get(0);
        assertThat(attempt.get("number")).isEqualTo(4);
        assertThat(attempt.get("errorCode")).isEqualTo("SIMULATED_TRANSIENT");

        MessageProperties props = applied(captureDlqPublish("email.dlq").postProcessor());
        assertThat((Object) props.getHeader("x-failure-class")).isEqualTo("TRANSIENT");
    }

    // ------------------------------------------------------------ métricas com gateway real (D-15)

    @Test
    void openCircuitBreakerCountsACircuitOpenRejectionAndAConfirmedRetry() {
        circuitBreakers.find("email").orElseThrow().transitionToOpenState();

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null);

        assertThat(meters.get("notify.delivery.rejections").tags("channel", "email", "reason", "circuit_open")
                .counter().count()).isEqualTo(1);
        assertThat(outcomeCount("retrying", "transient")).isEqualTo(1);
        assertThat(meters.get("notify.delivery.retries").tags("channel", "email", "level", "1")
                .counter().count()).isEqualTo(1);
        // recusa de ~0ms nunca entra no timer de envio
        assertThat(meters.find("notify.delivery.duration").timers().stream().mapToLong(t -> t.count()).sum())
                .isZero();
    }

    @Test
    void alwaysTransientCountsOneRetryPerLevelAndOneDeadLettered() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);

        for (int h = 0; h <= 3; h++) {
            claimReturns(processingDoc("welcome", h, h));
            failing.onEmail(message, h == 0 ? null : String.valueOf(h), null);
        }

        for (String level : List.of("1", "2", "3")) {
            assertThat(meters.get("notify.delivery.retries").tags("channel", "email", "level", level)
                    .counter().count()).as("level " + level).isEqualTo(1);
        }
        assertThat(outcomeCount("retrying", "transient")).isEqualTo(3);
        assertThat(outcomeCount("dead_lettered", "transient")).isEqualTo(1);
        assertThat(meters.get("notify.delivery.duration").tags("channel", "email", "outcome", "transient_failure")
                .timer().count()).isEqualTo(4);
    }

    @Test
    void alwaysPermanentCountsOneFailedPermanent() {
        claimReturns(processingDoc("welcome", 0, 0, "always-permanent@fail.test"));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null);

        assertThat(outcomeCount("failed", "permanent")).isEqualTo(1);
        assertThat(meters.get("notify.delivery.duration").tags("channel", "email", "outcome", "permanent_failure")
                .timer().count()).isEqualTo(1);
    }

    // ------------------------------------------------------------ circuit breaker e bulkhead (RES-01, D-05)

    @Test
    void openCircuitBreakerTurnsTheDeliveryIntoACircuitOpenRetryWithoutCallingTheAdapter() {
        CircuitBreaker emailBreaker = circuitBreakers.find("email").orElseThrow();
        emailBreaker.transitionToOpenState();

        assertThatCode(() -> listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null))
                .doesNotThrowAnyException();

        Document bson = MongoBson.update(captureRetryResult(0).update());
        Document set = bson.get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("RETRYING");
        assertThat(set.get("lastError", Document.class).getString("code")).isEqualTo("CIRCUIT_OPEN");
        Document attempt = (Document) bson.get("$push", Document.class)
                .get("attempts", Document.class).get("$each", List.class).get(0);
        assertThat(attempt.get("errorCode")).isEqualTo("CIRCUIT_OPEN");
        assertThat(attempt.get("failureClass")).isEqualTo("TRANSIENT");
        assertThat(attempt.get("outcome")).isEqualTo("TRANSIENT_FAILURE");
        assertThat(((Number) attempt.get("durationMs")).longValue()).isLessThan(50L);

        MessageProperties props = applied(captureRetryPublish("email.retry.5s").postProcessor());
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(1);
        verify(emailAdapter, never()).send(anyString(), any(), any());
        // recusa do CB nunca vira FAILED: nada na DLQ
        verify(rabbit, never()).convertAndSend(eq("notify.dlx"), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void closedCircuitBreakerLetsTheHappyPathReachSentThroughTheGateway() {
        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null);

        Document set = MongoBson.update(captureResult().update()).get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("SENT");
        CircuitBreaker emailBreaker = circuitBreakers.find("email").orElseThrow();
        assertThat(emailBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(emailBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(bulkheads.find("email").orElseThrow().getMetrics().getAvailableConcurrentCalls()).isEqualTo(8);
    }

    // ------------------------------------------------------------ reentrega depois do confirm (WR-01, WRK-03)

    private void verifyRetryPublishes(int times) {
        verify(rabbit, times(times)).convertAndSend(eq("notify.retry"), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void redeliveredOriginalAfterTheConfirmedTokenIsDiscardedWithoutSendingAgain() {
        SingleNotificationStore store = SingleNotificationStore.of(NotificationStatus.PENDING, 0, 0).install(mongo);
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);

        failing.onEmail(message, null, null);

        captureRetryPublish("email.retry.5s");
        assertThat(store.status()).isEqualTo("RETRYING");
        assertThat(store.retryCount()).isEqualTo(1);

        // o canal caiu depois do confirm do token 1 e antes do ack: a original volta com redelivered
        assertThatCode(() -> failing.onEmail(message, null, true)).doesNotThrowAnyException();

        verify(emailAdapter, times(1)).send(anyString(), any(), any());
        verifyRetryPublishes(1);
        assertThat(store.attempts()).hasSize(1);
        assertThat(store.attemptCount()).isEqualTo(1);

        // o próprio token 1 continua reivindicando o documento avançado
        failing.onEmail(message, "1", null);

        verify(emailAdapter, times(2)).send(anyString(), any(), any());
        captureRetryPublish("email.retry.25s");
        assertThat(store.status()).isEqualTo("RETRYING");
        assertThat(store.retryCount()).isEqualTo(2);
        assertThat(store.attempts()).hasSize(2);
    }

    @Test
    void secondCopyOfTheSameTokenIsDiscardedOnceTheNextLevelWasConfirmed() {
        SingleNotificationStore store = SingleNotificationStore.of(NotificationStatus.RETRYING, 1, 1).install(mongo);
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);

        failing.onEmail(message, "1", null);
        failing.onEmail(message, "1", null);

        verify(emailAdapter, times(1)).send(anyString(), any(), any());
        verifyRetryPublishes(1);
        captureRetryPublish("email.retry.25s");
        assertThat(store.status()).isEqualTo("RETRYING");
        assertThat(store.retryCount()).isEqualTo(2);
        assertThat(store.attempts()).hasSize(1);
        assertThat(store.attemptCount()).isEqualTo(2);
    }

    @Test
    void redeliveredOriginalStillRetriesWhenTheWorkerDiedBeforeTheRetryPublish() {
        // o worker caiu entre o update RETRYING e o publish: retryCount continua h (Armadilha 2)
        SingleNotificationStore store = SingleNotificationStore.of(NotificationStatus.RETRYING, 0, 1).install(mongo);
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);

        failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, true);

        verify(emailAdapter, times(1)).send(anyString(), any(), any());
        captureRetryPublish("email.retry.5s");
        assertThat(store.status()).isEqualTo("RETRYING");
        assertThat(store.retryCount()).isEqualTo(1);
        assertThat(store.attemptCount()).isEqualTo(2);
    }

    @Test
    void stuckProcessingIsStillResumedByARedeliveredCopy() {
        SingleNotificationStore store = SingleNotificationStore.of(NotificationStatus.PROCESSING, 0, 0,
                Instant.now().plus(Duration.ofMinutes(5)), "ana@exemplo.com").install(mongo);
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);

        // lease vigente: uma primeira entrega não toma o documento de um worker ativo
        listeners.onEmail(message, null, null);

        verify(emailAdapter, never()).send(anyString(), any(), any());
        assertThat(store.status()).isEqualTo("PROCESSING");

        listeners.onEmail(message, null, true);

        verify(emailAdapter, times(1)).send(anyString(), any(), any());
        assertThat(store.status()).isEqualTo("SENT");
        assertThat(store.attemptCount()).isEqualTo(1);
    }

    @Test
    void lostFenceOnTheAdvanceStillAcksWithoutTouchingTheDlq() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        // resultado RETRYING grava; o avanço depois do confirm perde o fence
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThatCode(() -> failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null))
                .doesNotThrowAnyException();

        verify(mongo, times(2)).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        verify(rabbit, never()).convertAndSend(eq("notify.dlx"), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        assertThat(meters.get("notify.delivery.retries").tags("channel", "email", "level", "1")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void storeFailureOnTheAdvanceStillAcksWithoutTouchingTheDlq() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null))
                .thenThrow(new DataAccessResourceFailureException("mongo fora do ar"));

        assertThatCode(() -> failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null, null))
                .doesNotThrowAnyException();

        verify(mongo, times(2)).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        verify(rabbit, never()).convertAndSend(eq("notify.dlx"), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        assertThat(meters.get("notify.delivery.retries").tags("channel", "email", "level", "1")
                .counter().count()).isEqualTo(1);
    }
}
