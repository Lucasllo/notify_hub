package dev.notifyhub.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.notifyhub.api.application.NotificationService;
import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.NotificationDocument;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Fatia web da criação e consulta (API-01, API-04): controller, serviço e publisher reais; Mongo e
 * Rabbit simulados. Sem Docker.
 */
@WebMvcTest(NotificationController.class)
@Import({NotificationService.class, NotificationPublisher.class})
@EnableConfigurationProperties(PublishProperties.class)
class NotificationApiFlowTest {

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final String VALID_BODY = """
            {"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    private void brokerAnswers(boolean ack, String reason) {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(4);
            cd.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void postReturns202WithLocationAndMinimalBody() throws Exception {
        brokerAnswers(true, null);

        MvcResult result = mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).matches("/notifications/" + UUID_PATTERN);
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(location).isEqualTo("/notifications/" + id);
    }

    @Test
    void insertsPendingBeforePublishingWithMessageIdAndRetryHeader() throws Exception {
        brokerAnswers(true, null);

        MvcResult result = mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        ArgumentCaptor<NotificationDocument> doc = ArgumentCaptor.forClass(NotificationDocument.class);
        ArgumentCaptor<NotificationMessage> msg = ArgumentCaptor.forClass(NotificationMessage.class);
        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        InOrder order = inOrder(mongoTemplate, rabbitTemplate);
        order.verify(mongoTemplate).insert(doc.capture());
        order.verify(rabbitTemplate).convertAndSend(eq("notify.exchange"), eq("notification.email"),
                msg.capture(), mpp.capture(), any(CorrelationData.class));

        assertThat(doc.getValue().id()).isEqualTo(id);
        assertThat(doc.getValue().status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(doc.getValue().recipient()).isEqualTo("ana@exemplo.com");
        assertThat(msg.getValue().notificationId()).isEqualTo(id);
        assertThat(msg.getValue().channel()).isEqualTo(Channel.EMAIL);
        assertThat(msg.getValue().templateCode()).isEqualTo("welcome");

        Message processed = mpp.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(processed.getMessageProperties().getMessageId()).isEqualTo(id);
        assertThat((Object) processed.getMessageProperties().getHeader("notify-retry-count")).isEqualTo(0);
    }

    @Test
    void getReturnsFullRepresentation() throws Exception {
        String id = "6f1c2a7e-1b2c-4d5e-8f90-123456789abc";
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        when(mongoTemplate.findById(id, NotificationDocument.class)).thenReturn(NotificationDocument.newPending(
                id, Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("name", "Ana"), now));

        mvc.perform(get("/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.recipient").value("ana@exemplo.com"))
                .andExpect(jsonPath("$.templateCode").value("welcome"))
                .andExpect(jsonPath("$.variables.name").value("Ana"))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.attempts").isArray())
                .andExpect(jsonPath("$.attempts").isEmpty())
                .andExpect(jsonPath("$.createdAt").value("2026-10-12T12:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-12T12:00:00Z"));
    }
}
