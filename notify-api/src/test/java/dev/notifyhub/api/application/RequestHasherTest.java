package dev.notifyhub.api.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.notifyhub.api.web.dto.CreateNotificationRequest;
import dev.notifyhub.common.domain.Channel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Hash do pedido canônico usado pela Idempotency-Key (D-06). */
class RequestHasherTest {

    /**
     * Hash fixo do pedido de referência (EMAIL, ana@exemplo.com, welcome, {name: Ana}). Se este teste
     * quebrar, a canonicalização mudou: todas as chaves já gravadas passariam a responder 422 para o
     * mesmo payload. Só atualize a constante de propósito, sabendo disso.
     */
    private static final String REFERENCE_HASH = "5f913d9c6f1eb824fd55e6e689847733b115da2d6c66aa36af32c91cdaadada3";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static CreateNotificationRequest request(Channel channel, String recipient, String templateCode,
            Map<String, Object> variables) {
        return new CreateNotificationRequest(channel, recipient, templateCode, variables);
    }

    private static CreateNotificationRequest reference() {
        return request(Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("name", "Ana"));
    }

    private static Map<String, Object> parse(String json) throws Exception {
        return JSON.readValue(json, new TypeReference<>() {
        });
    }

    @Test
    void sameRequestGivesTheSameLowercaseHexSha256() {
        String first = RequestHasher.hash(reference());
        String second = RequestHasher.hash(reference());

        assertThat(first).isEqualTo(second).matches("^[0-9a-f]{64}$");
    }

    @Test
    void variableInsertionOrderDoesNotChangeTheHash() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");

        assertThat(RequestHasher.hash(request(Channel.EMAIL, "ana@exemplo.com", "welcome", ab)))
                .isEqualTo(RequestHasher.hash(request(Channel.EMAIL, "ana@exemplo.com", "welcome", ba)));
    }

    @Test
    void anyDifferentFieldChangesTheHash() {
        String base = RequestHasher.hash(reference());

        assertThat(RequestHasher.hash(request(Channel.EMAIL, "bia@exemplo.com", "welcome", Map.of("name", "Ana"))))
                .isNotEqualTo(base);
        assertThat(RequestHasher.hash(request(Channel.EMAIL, "ana@exemplo.com", "order-shipped", Map.of("name", "Ana"))))
                .isNotEqualTo(base);
        assertThat(RequestHasher.hash(request(Channel.WEBHOOK, "ana@exemplo.com", "welcome", Map.of("name", "Ana"))))
                .isNotEqualTo(base);
        assertThat(RequestHasher.hash(request(Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("name", "Bia"))))
                .isNotEqualTo(base);
    }

    @Test
    void integerOneAndDecimalOneAreDifferent() {
        assertThat(RequestHasher.hash(request(Channel.SMS, "+5511999999999", "otp", Map.of("n", 1))))
                .isNotEqualTo(RequestHasher.hash(request(Channel.SMS, "+5511999999999", "otp", Map.of("n", 1.0))));
    }

    @Test
    void hashIsComputedAfterParsing() throws Exception {
        String oneDotZero = RequestHasher.hash(request(Channel.SMS, "+5511999999999", "otp", parse("{\"n\":1.0}")));
        String oneDotZeroZero = RequestHasher.hash(request(Channel.SMS, "+5511999999999", "otp", parse("{\"n\":1.00}")));
        String one = RequestHasher.hash(request(Channel.SMS, "+5511999999999", "otp", parse("{\"n\":1}")));

        assertThat(oneDotZero).isEqualTo(oneDotZeroZero);
        assertThat(one).isNotEqualTo(oneDotZero);
    }

    @Test
    void referenceRequestHashIsStable() {
        assertThat(RequestHasher.hash(reference())).isEqualTo(REFERENCE_HASH);
    }
}
