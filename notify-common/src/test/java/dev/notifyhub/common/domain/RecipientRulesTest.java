package dev.notifyhub.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RecipientRulesTest {

    private static final String EMAIL_255 = "a".repeat(243) + "@exemplo.com";
    private static final String URL_OVER_2048 = "https://fail.test/" + "a".repeat(2040);

    static Stream<Arguments> validRecipients() {
        return Stream.of(
                Arguments.of(Channel.EMAIL, "ana@exemplo.com"),
                Arguments.of(Channel.EMAIL, "always-transient@fail.test"),
                Arguments.of(Channel.SMS, "+5511999998888"),
                Arguments.of(Channel.SMS, "+5500000000001"),
                Arguments.of(Channel.WEBHOOK, "https://fail.test/x"),
                Arguments.of(Channel.WEBHOOK, "http://localhost:9000/hook"));
    }

    static Stream<Arguments> invalidRecipients() {
        return Stream.of(
                Arguments.of(Channel.EMAIL, "ana@"),
                Arguments.of(Channel.EMAIL, "ana exemplo.com"),
                Arguments.of(Channel.EMAIL, null),
                Arguments.of(Channel.EMAIL, ""),
                Arguments.of(Channel.EMAIL, EMAIL_255),
                Arguments.of(Channel.SMS, "123"),
                Arguments.of(Channel.SMS, "5511999998888"),
                Arguments.of(Channel.SMS, "+0123456789"),
                Arguments.of(Channel.SMS, null),
                Arguments.of(Channel.WEBHOOK, "ftp://x"),
                Arguments.of(Channel.WEBHOOK, "/relativo"),
                Arguments.of(Channel.WEBHOOK, "https://"),
                Arguments.of(Channel.WEBHOOK, URL_OVER_2048),
                Arguments.of(Channel.WEBHOOK, null));
    }

    @ParameterizedTest(name = "{0} aceita {1}")
    @MethodSource("validRecipients")
    void acceptsValidRecipients(Channel channel, String recipient) {
        assertThat(RecipientRules.isValid(channel, recipient)).isTrue();
    }

    @ParameterizedTest(name = "{0} rejeita {1}")
    @MethodSource("invalidRecipients")
    void rejectsInvalidRecipients(Channel channel, String recipient) {
        assertThat(RecipientRules.isValid(channel, recipient)).isFalse();
    }

    @Test
    void sanityCheckOnBoundaryFixtures() {
        assertThat(EMAIL_255).hasSize(255);
        assertThat(URL_OVER_2048.length()).isGreaterThan(2048);
    }

    @Test
    void masksEmailKeepingOnlyFirstLetterAndDomain() {
        String masked = RecipientRules.mask(Channel.EMAIL, "ana@exemplo.com");
        assertThat(masked).doesNotContain("ana@").endsWith("@exemplo.com");
    }

    @Test
    void masksPhoneMiddleDigits() {
        assertThat(RecipientRules.mask(Channel.SMS, "+5511999998888")).doesNotContain("99999");
    }

    @Test
    void masksWebhookPathAndQuery() {
        assertThat(RecipientRules.mask(Channel.WEBHOOK, "https://fail.test/a/b?token=x"))
                .doesNotContain("token")
                .startsWith("https://fail.test");
    }

    @Test
    void masksInvalidInputCompletely() {
        assertThat(RecipientRules.mask(Channel.EMAIL, "nao-e-email")).isEqualTo("***");
        assertThat(RecipientRules.mask(Channel.SMS, null)).isEqualTo("***");
    }

    @Test
    void describesSmsFormatAsE164() {
        assertThat(RecipientRules.describe(Channel.SMS)).contains("E.164");
    }
}
