package dev.notifyhub.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.impl.LongStringHelper;
import dev.notifyhub.api.admin.DlqReprocessService.FailureReason;
import dev.notifyhub.api.admin.DlqReprocessService.Report;
import dev.notifyhub.api.application.NotificationResetService;
import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.NotificationDocument;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.RabbitExceptionTranslator;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

/**
 * Fluxo do reprocessamento da DLQ (ADM-01, ADM-02, D-02, D-03) com os componentes reais
 * ({@link DlqReprocessService}, {@link NotificationResetService}, {@link NotificationPublisher}) sobre
 * mocks do {@link RabbitTemplate}, do {@link AmqpAdmin}, do {@link MongoTemplate} e do canal AMQP.
 */
class DlqReprocessFlowTest {

    private static final String DLQ = "notify.email.dlq";
    private static final String ID1 = "6f1c2a7e-1b2c-4d5e-8f90-000000000001";
    private static final String ID2 = "6f1c2a7e-1b2c-4d5e-8f90-000000000002";
    private static final String ID3 = "6f1c2a7e-1b2c-4d5e-8f90-000000000003";
    private static final Instant CREATED = Instant.parse("2026-10-01T10:00:00Z");
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final AmqpAdmin admin = mock(AmqpAdmin.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final com.rabbitmq.client.Channel ch = mock(com.rabbitmq.client.Channel.class);
    private final DlqReprocessService service = new DlqReprocessService(rabbit, admin,
            new NotificationResetService(mongo), new NotificationPublisher(rabbit), json,
            new PublishProperties(Duration.ofMillis(50), Duration.ofSeconds(5)));

    /**
     * Roda o callback no canal mockado e, como o {@code RabbitTemplate.execute} real, converte qualquer
     * exceção que escape do callback numa {@code AmqpException}.
     */
    @BeforeEach
    void runCallbacksOnTheMockedChannel() {
        when(rabbit.execute(any(ChannelCallback.class))).thenAnswer(inv -> {
            try {
                return inv.<ChannelCallback<?>>getArgument(0).doInRabbit(ch);
            } catch (Exception e) {
                throw RabbitExceptionTranslator.convertRabbitAccessException(e);
            }
        });
    }

    @AfterEach
    void dlqNeverRejectsNorDropsADelivery() throws Exception {
        verify(ch, never()).basicReject(anyLong(), anyBoolean());
        verify(ch, never()).basicNack(anyLong(), anyBoolean(), eq(false));
    }

    // ---------------------------------------------------------------- helpers

    private static NotificationDocument doc(String id, NotificationStatus status, int retryCount, int attemptCount) {
        return new NotificationDocument(id, Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("name", "Ana"),
                status, retryCount, attemptCount, null, null, List.of(), CREATED, CREATED, null, null, null);
    }

    private void dlqReady(int ready) {
        when(admin.getQueueInfo(DLQ)).thenReturn(new QueueInformation(DLQ, ready, 0));
    }

    private byte[] body(String id, Channel channel) throws Exception {
        return json.writeValueAsBytes(new NotificationMessage(id, channel, "welcome", CREATED));
    }

    private static Map<String, Object> dlqHeaders() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("x-failure-class", LongStringHelper.asLongString("TRANSIENT"));
        headers.put("notify-retry-count", 3);
        headers.put("__TypeId__", LongStringHelper.asLongString("dev.notifyhub.common.messaging.NotificationMessage"));
        headers.put("x-death", List.of(Map.of("count", 1L, "queue", "notify.email")));
        return headers;
    }

    private static GetResponse delivery(long tag, String messageId, byte[] body) {
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .messageId(messageId)
                .contentType("application/json")
                .headers(dlqHeaders())
                .build();
        return new GetResponse(new Envelope(tag, false, "notify.dlx", "email.dlq"), props, body, 0);
    }

    private GetResponse delivery(long tag, String id) throws Exception {
        return delivery(tag, id, body(id, Channel.EMAIL));
    }

    private void stored(NotificationDocument doc) {
        when(mongo.findById(doc.id(), NotificationDocument.class)).thenReturn(doc);
    }

    private void resetReturns(NotificationDocument first, NotificationDocument... more) {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(NotificationDocument.class))).thenReturn(first, more);
    }

    private static NotificationDocument afterReset(NotificationDocument before) {
        return doc(before.id(), NotificationStatus.PENDING, 0, before.attemptCount());
    }

    private void onPublish(Consumer<CorrelationData> broker) {
        doAnswer(inv -> {
            broker.accept(inv.getArgument(4));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private void onCopy(Consumer<CorrelationData> broker) {
        doAnswer(inv -> {
            broker.accept(inv.getArgument(3));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private static void ack(CorrelationData cd) {
        cd.getFuture().complete(new CorrelationData.Confirm(true, null));
    }

    private static void nack(CorrelationData cd) {
        cd.getFuture().complete(new CorrelationData.Confirm(false, "simulated nack"));
    }

    private static ReprocessInterruptedException interrupted(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ReprocessInterruptedException.class);
        return (ReprocessInterruptedException) thrown;
    }

    // ---------------------------------------------------------------- caminho feliz

    @Test
    void deadLetteredGoesBackToTheMainQueueResetBeforePublishAckAfter() throws Exception {
        NotificationDocument dead = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        stored(dead);
        resetReturns(afterReset(dead));
        onPublish(DlqReprocessFlowTest::ack);

        Report report = service.reprocess(Channel.EMAIL, 10);

        assertThat(report).isEqualTo(new Report(10, 1, 0, 0));
        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> cd = ArgumentCaptor.forClass(CorrelationData.class);
        InOrder order = inOrder(mongo, rabbit, ch);
        order.verify(mongo).findById(ID1, NotificationDocument.class);
        order.verify(mongo).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(NotificationDocument.class));
        order.verify(rabbit).convertAndSend(eq("notify.exchange"), eq("notification.email"),
                eq(new NotificationMessage(ID1, Channel.EMAIL, "welcome", CREATED)), mpp.capture(), cd.capture());
        order.verify(ch).basicAck(7, false);

        Message published = mpp.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(published.getMessageProperties().getMessageId()).isEqualTo(ID1);
        assertThat((Object) published.getMessageProperties().getHeader("notify-retry-count")).isEqualTo(0);
        assertThat(cd.getValue().getId()).isNotEqualTo(ID1).matches(UUID_PATTERN);
    }

    @Test
    void resetIsFencedByTheReadStatusAndRetryCountAndNeverTouchesAttemptCount() throws Exception {
        NotificationDocument dead = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        stored(dead);
        resetReturns(afterReset(dead));
        onPublish(DlqReprocessFlowTest::ack);

        service.reprocess(Channel.EMAIL, 10);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<FindAndModifyOptions> options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongo).findAndModify(query.capture(), update.capture(), options.capture(),
                eq(NotificationDocument.class));

        Document filter = query.getValue().getQueryObject();
        assertThat(filter.keySet()).containsExactlyInAnyOrder("_id", "status", "retryCount");
        assertThat(filter.get("_id")).isEqualTo(ID1);
        assertThat(String.valueOf(filter.get("status"))).isEqualTo("DEAD_LETTERED");
        assertThat(filter.get("retryCount")).isEqualTo(3);

        Document updateObject = update.getValue().getUpdateObject();
        Document set = (Document) updateObject.get("$set");
        assertThat(set.keySet()).containsExactlyInAnyOrder("status", "retryCount", "updatedAt");
        assertThat(set.get("status")).isEqualTo(NotificationStatus.PENDING);
        assertThat(set.get("retryCount")).isEqualTo(0);
        assertThat(set.get("updatedAt")).isInstanceOf(Instant.class);
        Document unset = (Document) updateObject.get("$unset");
        assertThat(unset.keySet()).containsExactlyInAnyOrder("leaseUntil", "claimedAt");
        assertThat(updateObject.keySet()).doesNotContain("$inc");
        for (String operator : updateObject.keySet()) {
            assertThat(((Document) updateObject.get(operator)).keySet())
                    .as("operador %s", operator).doesNotContain("attemptCount");
        }
        assertThat(options.getValue().isReturnNew()).isTrue();
    }

    @Test
    void failedAlsoResetsBecauseTheTableAllowsFailedToPending() throws Exception {
        NotificationDocument failed = doc(ID1, NotificationStatus.FAILED, 0, 1);
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        stored(failed);
        resetReturns(afterReset(failed));
        onPublish(DlqReprocessFlowTest::ack);

        assertThat(service.reprocess(Channel.EMAIL, 10)).isEqualTo(new Report(10, 1, 0, 0));
        verify(ch).basicAck(7, false);
    }

    // ---------------------------------------------------------------- orçamento

    @Test
    void budgetIsTheReadyCountWhenSmallerThanMax() throws Exception {
        NotificationDocument d1 = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        NotificationDocument d2 = doc(ID2, NotificationStatus.DEAD_LETTERED, 3, 4);
        NotificationDocument d3 = doc(ID3, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(2);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(1, ID1), delivery(2, ID2), delivery(3, ID3));
        stored(d1);
        stored(d2);
        stored(d3);
        resetReturns(afterReset(d1), afterReset(d2), afterReset(d3));
        onPublish(DlqReprocessFlowTest::ack);

        assertThat(service.reprocess(Channel.EMAIL, 10)).isEqualTo(new Report(10, 2, 0, 0));
        verify(ch, times(2)).basicGet(DLQ, false);
    }

    @Test
    void budgetIsMaxWhenSmallerThanTheReadyCount() throws Exception {
        NotificationDocument d1 = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(3);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(1, ID1), delivery(2, ID2), delivery(3, ID3));
        stored(d1);
        resetReturns(afterReset(d1));
        onPublish(DlqReprocessFlowTest::ack);

        assertThat(service.reprocess(Channel.EMAIL, 1)).isEqualTo(new Report(1, 1, 0, 0));
        verify(ch, times(1)).basicGet(DLQ, false);
    }

    @Test
    void emptyDlqAnswersZeroCountsWithoutOpeningAChannel() {
        dlqReady(0);

        assertThat(service.reprocess(Channel.EMAIL, 10)).isEqualTo(new Report(10, 0, 0, 0));
        verify(rabbit, never()).execute(any(ChannelCallback.class));
    }

    // ---------------------------------------------------------------- parada na primeira falha

    @Test
    void publishNackPutsTheMessageBackAndStopsWithAClosedReason() throws Exception {
        NotificationDocument d1 = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(2);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1), delivery(8, ID2));
        stored(d1);
        resetReturns(afterReset(d1));
        onPublish(DlqReprocessFlowTest::nack);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.reprocess(Channel.EMAIL, 10));

        ReprocessInterruptedException e = interrupted(thrown);
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.getReport()).isEqualTo(new Report(10, 0, 0, 0));
        assertThat(e.getReason()).isEqualTo(FailureReason.PUBLISH_NACK);
        assertThat(e.getBody().getProperties()).containsEntry("requested", 10).containsEntry("reprocessed", 0)
                .containsEntry("reason", "PUBLISH_NACK");
        assertThat(e.getBody().getDetail()).doesNotContain("simulated nack");
        verify(ch).basicNack(7, false, true);
        verify(ch, never()).basicAck(eq(7L), anyBoolean());
        verify(ch, times(1)).basicGet(DLQ, false);
    }

    @Test
    void returnedPublishStopsWithPublishReturned() throws Exception {
        NotificationDocument d1 = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        stored(d1);
        resetReturns(afterReset(d1));
        onPublish(cd -> {
            Message msg = new Message(new byte[0], new MessageProperties());
            cd.setReturned(new ReturnedMessage(msg, 312, "NO_ROUTE", "notify.exchange", "notification.email"));
            ack(cd);
        });

        assertThatThrownBy(() -> service.reprocess(Channel.EMAIL, 10))
                .isInstanceOfSatisfying(ReprocessInterruptedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(FailureReason.PUBLISH_RETURNED));
        verify(ch).basicNack(7, false, true);
    }

    @Test
    void confirmThatNeverArrivesStopsWithPublishTimeout() throws Exception {
        NotificationDocument d1 = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        stored(d1);
        resetReturns(afterReset(d1));
        onPublish(cd -> { });

        assertThatThrownBy(() -> service.reprocess(Channel.EMAIL, 10))
                .isInstanceOfSatisfying(ReprocessInterruptedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(FailureReason.PUBLISH_TIMEOUT));
        verify(ch).basicNack(7, false, true);
    }

    // ---------------------------------------------------------------- skipped: cópia para o fim da DLQ

    @Test
    void statusTheTableCannotResetIsCopiedToTheDlqTailAndSkipped() throws Exception {
        byte[] original = body(ID1, Channel.EMAIL);
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1, original));
        stored(doc(ID1, NotificationStatus.RETRYING, 2, 3));
        onCopy(DlqReprocessFlowTest::ack);

        assertThat(service.reprocess(Channel.EMAIL, 10)).isEqualTo(new Report(10, 0, 1, 0));

        ArgumentCaptor<Message> copy = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> cd = ArgumentCaptor.forClass(CorrelationData.class);
        InOrder order = inOrder(rabbit, ch);
        order.verify(rabbit).send(eq("notify.dlx"), eq("email.dlq"), copy.capture(), cd.capture());
        order.verify(ch).basicAck(7, false);
        assertThat(copy.getValue().getBody()).isEqualTo(original);
        MessageProperties props = copy.getValue().getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(ID1);
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat(props.getHeaders()).containsOnlyKeys("x-failure-class", "notify-retry-count");
        assertThat((Object) props.getHeader("x-failure-class")).isEqualTo("TRANSIENT");
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(3);
        assertThat(cd.getValue().getId()).isNotEqualTo(ID1).matches(UUID_PATTERN);
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        verify(mongo, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(NotificationDocument.class));
    }

    @Test
    void lostFenceIsCopiedToTheDlqTailAndSkipped() throws Exception {
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        stored(doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4));
        resetReturns(null);
        onCopy(DlqReprocessFlowTest::ack);

        assertThat(service.reprocess(Channel.EMAIL, 10)).isEqualTo(new Report(10, 0, 1, 0));
        verify(rabbit).send(eq("notify.dlx"), eq("email.dlq"), any(Message.class), any(CorrelationData.class));
        verify(ch).basicAck(7, false);
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void copyNackPutsTheMessageBackAndStops() throws Exception {
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        stored(doc(ID1, NotificationStatus.RETRYING, 2, 3));
        onCopy(DlqReprocessFlowTest::nack);

        assertThatThrownBy(() -> service.reprocess(Channel.EMAIL, 10))
                .isInstanceOfSatisfying(ReprocessInterruptedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(FailureReason.PUBLISH_NACK);
                    assertThat(e.getReport()).isEqualTo(new Report(10, 0, 0, 0));
                });
        verify(ch).basicNack(7, false, true);
        verify(ch, never()).basicAck(eq(7L), anyBoolean());
    }

    // ---------------------------------------------------------------- evento REPROCESSED (ADM-02)

    @Test
    void resetPushesOneReprocessedEventKeepingTheLastFiftyEntries() throws Exception {
        NotificationDocument dead = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        stored(dead);
        resetReturns(afterReset(dead));
        onPublish(DlqReprocessFlowTest::ack);

        service.reprocess(Channel.EMAIL, 10);

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class),
                eq(NotificationDocument.class));
        Document bson = MongoBson.update(update.getValue());
        assertThat(bson.keySet()).containsExactlyInAnyOrder("$set", "$unset", "$push");
        for (String operator : bson.keySet()) {
            assertThat(bson.get(operator, Document.class).keySet()).as("operador %s", operator)
                    .doesNotContain("attemptCount");
        }
        Document push = bson.get("$push", Document.class).get("attempts", Document.class);
        assertThat(push.get("$slice")).isEqualTo(-50);
        List<?> each = push.get("$each", List.class);
        assertThat(each).hasSize(1);
        Document event = (Document) each.get(0);
        assertThat(event.get("number")).isEqualTo(0);
        assertThat(event.get("retryLevel")).isEqualTo(3);
        assertThat(event.get("durationMs")).isEqualTo(0L);
        assertThat(event.get("outcome")).isEqualTo("REPROCESSED");
        assertThat(event.get("errorCode")).isNull();
        assertThat(event.get("failureClass")).isNull();
        assertThat(event.getString("errorMessage")).contains("DEAD_LETTERED");
        assertThat(event.getString("thread")).isNotBlank();
    }

    // ---------------------------------------------------------------- discarded (D-02)

    @Test
    void missingDocumentIsAckedAndDiscarded() throws Exception {
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));

        assertThat(service.reprocess(Channel.EMAIL, 10)).isEqualTo(new Report(10, 0, 0, 1));
        verify(ch).basicAck(7, false);
        verifyNothingPublished();
    }

    @Test
    void sentDocumentIsAckedAndDiscarded() throws Exception {
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        stored(doc(ID1, NotificationStatus.SENT, 0, 1));

        assertThat(service.reprocess(Channel.EMAIL, 10)).isEqualTo(new Report(10, 0, 0, 1));
        verify(ch).basicAck(7, false);
        verifyNothingPublished();
    }

    private void verifyNothingPublished() {
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    // ---------------------------------------------------------------- corpo inválido: preservado no fim

    @Test
    void unreadableBodyIsCopiedToTheDlqTailWithoutTouchingMongo() throws Exception {
        assertInvalidBodyIsPreserved("isto não é json".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void bodyWithoutNotificationIdIsCopiedToTheDlqTailWithoutTouchingMongo() throws Exception {
        assertInvalidBodyIsPreserved(
                json.writeValueAsBytes(new NotificationMessage(null, Channel.EMAIL, "welcome", CREATED)));
    }

    @Test
    void bodyOfAnotherChannelIsCopiedToTheDlqTailWithoutTouchingMongo() throws Exception {
        assertInvalidBodyIsPreserved(body(ID1, Channel.SMS));
    }

    private void assertInvalidBodyIsPreserved(byte[] original) throws Exception {
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1, original));
        onCopy(DlqReprocessFlowTest::ack);

        assertThat(service.reprocess(Channel.EMAIL, 10)).isEqualTo(new Report(10, 0, 1, 0));

        ArgumentCaptor<Message> copy = ArgumentCaptor.forClass(Message.class);
        InOrder order = inOrder(rabbit, ch);
        order.verify(rabbit).send(eq("notify.dlx"), eq("email.dlq"), copy.capture(), any(CorrelationData.class));
        order.verify(ch).basicAck(7, false);
        assertThat(copy.getValue().getBody()).isEqualTo(original);
        verify(mongo, never()).findById(any(), any());
    }

    // ---------------------------------------------------------------- falhas de infraestrutura (D-03)

    @Test
    void mongoDownOnTheReadOfTheSecondMessageRequeuesItAndStops() throws Exception {
        NotificationDocument d1 = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(2);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1), delivery(8, ID2));
        stored(d1);
        when(mongo.findById(ID2, NotificationDocument.class))
                .thenThrow(new DataAccessResourceFailureException("mongo fora"));
        resetReturns(afterReset(d1));
        onPublish(DlqReprocessFlowTest::ack);

        assertThatThrownBy(() -> service.reprocess(Channel.EMAIL, 10))
                .isInstanceOfSatisfying(ReprocessInterruptedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(FailureReason.MONGO_UNAVAILABLE);
                    assertThat(e.getReport()).isEqualTo(new Report(10, 1, 0, 0));
                    assertThat(e.getBody().getDetail()).doesNotContain("mongo fora");
                });
        verify(ch).basicAck(7, false);
        verify(ch).basicNack(8, false, true);
        verify(ch, never()).basicAck(eq(8L), anyBoolean());
    }

    @Test
    void mongoDownOnTheResetOfTheSecondMessageRequeuesItAndStops() throws Exception {
        NotificationDocument d1 = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        NotificationDocument d2 = doc(ID2, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(2);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1), delivery(8, ID2));
        stored(d1);
        stored(d2);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(NotificationDocument.class)))
                .thenReturn(afterReset(d1))
                .thenThrow(new DataAccessResourceFailureException("mongo fora"));
        onPublish(DlqReprocessFlowTest::ack);

        assertThatThrownBy(() -> service.reprocess(Channel.EMAIL, 10))
                .isInstanceOfSatisfying(ReprocessInterruptedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(FailureReason.MONGO_UNAVAILABLE);
                    assertThat(e.getReport()).isEqualTo(new Report(10, 1, 0, 0));
                });
        verify(ch).basicNack(8, false, true);
    }

    @Test
    void missingDlqIsDlqUnavailableWithoutOpeningAChannel() {
        when(admin.getQueueInfo(DLQ)).thenReturn(null);

        assertThatThrownBy(() -> service.reprocess(Channel.EMAIL, 10))
                .isInstanceOfSatisfying(ReprocessInterruptedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(FailureReason.DLQ_UNAVAILABLE);
                    assertThat(e.getReport()).isEqualTo(new Report(10, 0, 0, 0));
                });
        verify(rabbit, never()).execute(any(ChannelCallback.class));
    }

    @Test
    void brokerDownOnTheQueueInfoIsBrokerUnavailable() {
        when(admin.getQueueInfo(DLQ)).thenThrow(new AmqpConnectException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> service.reprocess(Channel.EMAIL, 10))
                .isInstanceOfSatisfying(ReprocessInterruptedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(FailureReason.BROKER_UNAVAILABLE);
                    assertThat(e.getReport()).isEqualTo(new Report(10, 0, 0, 0));
                });
        verify(rabbit, never()).execute(any(ChannelCallback.class));
    }

    @Test
    void channelThatFailsAfterOneReprocessedIsBrokerUnavailableWithTheAccumulatedCounts() throws Exception {
        NotificationDocument d1 = doc(ID1, NotificationStatus.DEAD_LETTERED, 3, 4);
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        stored(d1);
        resetReturns(afterReset(d1));
        onPublish(DlqReprocessFlowTest::ack);
        when(rabbit.execute(any(ChannelCallback.class))).thenAnswer(inv -> {
            inv.<ChannelCallback<?>>getArgument(0).doInRabbit(ch);
            throw new AmqpIOException(new IOException("canal fechado"));
        });

        assertThatThrownBy(() -> service.reprocess(Channel.EMAIL, 10))
                .isInstanceOfSatisfying(ReprocessInterruptedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(FailureReason.BROKER_UNAVAILABLE);
                    assertThat(e.getReport()).isEqualTo(new Report(10, 1, 0, 0));
                });
    }

    @Test
    void unexpectedExceptionRequeuesTheDeliveryAndReachesTheCallerUnchanged() throws Exception {
        IllegalStateException bug = new IllegalStateException("bug inesperado");
        dlqReady(1);
        when(ch.basicGet(DLQ, false)).thenReturn(delivery(7, ID1));
        when(mongo.findById(ID1, NotificationDocument.class)).thenThrow(bug);

        assertThatThrownBy(() -> service.reprocess(Channel.EMAIL, 10)).isSameAs(bug);
        verify(ch).basicNack(7, false, true);
        verify(ch, never()).basicAck(eq(7L), anyBoolean());
    }
}
