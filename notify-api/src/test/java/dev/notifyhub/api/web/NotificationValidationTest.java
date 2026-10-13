package dev.notifyhub.api.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.notifyhub.api.application.NotificationService;
import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.common.persistence.NotificationDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Entrada inválida → 400 Problem Details com {@code errors[]} por campo, sem nenhuma gravação no
 * Mongo (API-06); id inexistente → 404 Problem Detail.
 */
@WebMvcTest(NotificationController.class)
@Import({NotificationService.class, NotificationPublisher.class})
@EnableConfigurationProperties(PublishProperties.class)
class NotificationValidationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MongoTemplate mongoTemplate;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    private static Map<String, Object> body(Object channel, Object recipient, Object templateCode, Object variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (channel != null) {
            body.put("channel", channel);
        }
        if (recipient != null) {
            body.put("recipient", recipient);
        }
        if (templateCode != null) {
            body.put("templateCode", templateCode);
        }
        if (variables != null) {
            body.put("variables", variables);
        }
        return body;
    }

    private static Map<String, Object> email(Map<String, Object> variables) {
        return body("EMAIL", "ana@exemplo.com", "welcome", variables);
    }

    private static Arguments invalid(String name, Map<String, Object> body, String field) {
        return Arguments.of(name, body, field);
    }

    static Stream<Arguments> invalidRequests() {
        Map<String, Object> fiftyOne = new LinkedHashMap<>();
        IntStream.rangeClosed(1, 51).forEach(i -> fiftyOne.put("v" + i, "x"));
        return Stream.of(
                invalid("canal desconhecido", body("PUSH", "ana@exemplo.com", "welcome", Map.of()), "channel"),
                invalid("sem recipient", body("EMAIL", null, "welcome", Map.of()), "recipient"),
                invalid("sem templateCode", body("EMAIL", "ana@exemplo.com", null, Map.of()), "templateCode"),
                invalid("variables ausente", body("EMAIL", "ana@exemplo.com", "welcome", null), "variables"),
                invalid("SMS fora do E.164", body("SMS", "123", "welcome", Map.of()), "recipient"),
                invalid("e-mail sem domínio", body("EMAIL", "ana@", "welcome", Map.of()), "recipient"),
                invalid("webhook ftp", body("WEBHOOK", "ftp://x", "welcome", Map.of()), "recipient"),
                invalid("chave com ponto", email(Map.of("a.b", "x")), "variables"),
                invalid("chave reservada", email(Map.of("_notificationId", "x")), "variables"),
                invalid("chave com operador", email(Map.of("$where", "x")), "variables"),
                invalid("51 variáveis", email(fiftyOne), "variables"),
                invalid("valor objeto aninhado", email(Map.of("name", Map.of("first", "Ana"))), "variables"),
                invalid("string de 1001", email(Map.of("name", "a".repeat(1001))), "variables"));
    }

    private ResultActions postJson(String json) throws Exception {
        return mvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** O RabbitTemplate mockado confirma (ack) todo publish na hora. */
    private void brokerAcksEveryPublish() {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(4);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private static void expectValidationProblem(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:notify-hub:problem:validation-error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/notifications"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void invalidRequestIs400WithFieldErrorAndNoWrite(String name, Map<String, Object> body, String field)
            throws Exception {
        ResultActions result = postJson(JSON.writeValueAsString(body));

        expectValidationProblem(result);
        result.andExpect(jsonPath("$.errors[*].field", hasItem(field)));
        verifyNoInteractions(mongoTemplate, rabbitTemplate);
    }

    @Test
    void smsErrorMessageCitesE164() throws Exception {
        postJson(JSON.writeValueAsString(body("SMS", "123", "welcome", Map.of())))
                .andExpect(jsonPath("$.errors[0].field").value("recipient"))
                .andExpect(jsonPath("$.errors[0].message", containsString("E.164")));
    }

    @Test
    void unknownChannelListsAcceptedValues() throws Exception {
        postJson(JSON.writeValueAsString(body("PUSH", "ana@exemplo.com", "welcome", Map.of())))
                .andExpect(jsonPath("$.errors[0].field").value("channel"))
                .andExpect(jsonPath("$.errors[0].message").value("valores aceitos: EMAIL, SMS, WEBHOOK"));
    }

    /**
     * Ordinais do enum não são canal (D-01): o destinatário é válido para o canal que o ordinal
     * produziria, então sem binding estrito a requisição passaria pela validação e seria gravada.
     */
    static Stream<Arguments> numericChannels() {
        return Stream.of(
                Arguments.of("ordinal 1 (seria SMS)", 1, "+5511999998888"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("numericChannels")
    void numericChannelIs400WithOnlyChannelErrorAndNoWrite(String name, Object channel, String recipient)
            throws Exception {
        // Broker saudável: se o ordinal virasse canal, a resposta seria 202, nunca um 400 de outra origem
        brokerAcksEveryPublish();

        ResultActions result = postJson(
                JSON.writeValueAsString(body(channel, recipient, "welcome", Map.of("name", "Ana"))));

        expectValidationProblem(result);
        result.andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("channel"))
                .andExpect(jsonPath("$.errors[0].message").value("valores aceitos: EMAIL, SMS, WEBHOOK"));
        verifyNoInteractions(mongoTemplate, rabbitTemplate);
    }

    @Test
    void malformedJsonIs400Problem() throws Exception {
        ResultActions result = postJson("{\"channel\": \"EMAIL\", ");

        expectValidationProblem(result);
        result.andExpect(jsonPath("$.errors[0].message").value("corpo JSON inválido"))
                .andExpect(jsonPath("$.detail").value("Um ou mais campos são inválidos."));
        verifyNoInteractions(mongoTemplate, rabbitTemplate);
    }

    static Stream<Arguments> magicRecipients() {
        return Stream.of(
                Arguments.of("EMAIL", "always-transient@fail.test"),
                Arguments.of("SMS", "+5500000000001"),
                Arguments.of("WEBHOOK", "https://fail.test/x"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("magicRecipients")
    void magicRecipientsAreAccepted(String channel, String recipient) throws Exception {
        brokerAcksEveryPublish();

        postJson(JSON.writeValueAsString(body(channel, recipient, "welcome", Map.of("name", "Ana"))))
                .andExpect(status().isAccepted());
    }

    @Test
    void unknownIdIs404Problem() throws Exception {
        when(mongoTemplate.findById("nao-existe", NotificationDocument.class)).thenReturn(null);

        mvc.perform(get("/notifications/{id}", "nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:notify-hub:problem:not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.instance").value("/notifications/nao-existe"));
    }
}
