package dev.notifyhub.worker.channel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.RecipientRules;
import dev.notifyhub.worker.channel.MagicRecipients.Kind;
import dev.notifyhub.worker.channel.MagicRecipients.MagicBehavior;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * Tabela dos destinatários mágicos (D-01) e dos parecidos que não são mágicos (T-02-17), sem Spring.
 */
class MagicRecipientsTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "EMAIL,   always-transient@fail.test,          ALWAYS_TRANSIENT",
            "EMAIL,   ALWAYS-TRANSIENT@FAIL.TEST,          ALWAYS_TRANSIENT",
            "WEBHOOK, https://fail.test/always-transient,  ALWAYS_TRANSIENT",
            "WEBHOOK, http://fail.test/always-transient/x, ALWAYS_TRANSIENT",
            "WEBHOOK, HTTPS://FAIL.TEST/Always-Transient,  ALWAYS_TRANSIENT",
            "SMS,     +550000000001,                       ALWAYS_TRANSIENT",
            "EMAIL,   always-permanent@fail.test,          ALWAYS_PERMANENT",
            "WEBHOOK, https://fail.test/always-permanent,  ALWAYS_PERMANENT",
            "SMS,     +550000000002,                       ALWAYS_PERMANENT",
            "EMAIL,   fail-until-reprocess@fail.test,         FAIL_UNTIL_REPROCESS",
            "WEBHOOK, https://fail.test/fail-until-reprocess, FAIL_UNTIL_REPROCESS",
            "SMS,     +550000000004,                          FAIL_UNTIL_REPROCESS",
    })
    void recognizesTheReservedRecipients(Channel channel, String recipient, Kind kind) {
        assertThat(MagicRecipients.parse(channel, recipient)).map(MagicBehavior::kind).contains(kind);
    }

    @ParameterizedTest(name = "{0} {1} → falha {2} vezes")
    @CsvSource({
            "EMAIL,   fail-3-then-ok@fail.test,         3",
            "EMAIL,   fail-1-then-ok@fail.test,         1",
            "EMAIL,   fail-99-then-ok@fail.test,        99",
            "WEBHOOK, https://fail.test/fail-3-then-ok, 3",
            "SMS,     +550000000133,                    3",
            "SMS,     +550000000123,                    2",
            "SMS,     +550000000113,                    1",
            "SMS,     +550000000193,                    9",
    })
    void failNThenOkCarriesTheNumberOfFailures(Channel channel, String recipient, int failures) {
        assertThat(MagicRecipients.parse(channel, recipient))
                .contains(new MagicBehavior(Kind.FAIL_N_THEN_OK, failures));
    }

    @ParameterizedTest(name = "{0} {1} não é mágico")
    @CsvSource({
            "EMAIL,   always-transient@fail.test.com.br",
            "EMAIL,   always-transient@notfail.test",
            "EMAIL,   always-transient@sub.fail.test",
            "EMAIL,   ana@exemplo.com",
            "EMAIL,   ana@fail.test",
            "EMAIL,   fail-0-then-ok@fail.test",
            "EMAIL,   fail-07-then-ok@fail.test",
            "EMAIL,   fail-100-then-ok@fail.test",
            "EMAIL,   fail-x-then-ok@fail.test",
            "EMAIL,   @fail.test",
            "WEBHOOK, https://fail.test.evil.com/always-transient",
            "WEBHOOK, https://example.com/fail.test/always-transient",
            "WEBHOOK, https://fail.test/",
            "WEBHOOK, https://fail.test",
            "WEBHOOK, https://fail.test/x/always-transient",
            "WEBHOOK, ftp://fail.test/always-transient",
            "WEBHOOK, https://hooks.exemplo.com/notify",
            "WEBHOOK, not a uri",
            "SMS,     +5500000000010",
            "SMS,     +550000000005",
            "SMS,     +550000000103",
            "SMS,     +550000000000",
            "SMS,     +550000000124",
            "SMS,     +5511999998888",
            "SMS,     550000000001",
    })
    void lookalikesAreNotMagic(Channel channel, String recipient) {
        assertThat(MagicRecipients.parse(channel, recipient)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void nullAndEmptyAreNotMagicOnAnyChannel(String recipient) {
        for (Channel channel : Channel.values()) {
            assertThat(MagicRecipients.parse(channel, recipient)).isEmpty();
        }
    }

    @Test
    void aMagicRecipientOfOneChannelIsNotMagicOnAnother() {
        assertThat(MagicRecipients.parse(Channel.WEBHOOK, "always-transient@fail.test")).isEmpty();
        assertThat(MagicRecipients.parse(Channel.EMAIL, "https://fail.test/always-transient")).isEmpty();
        assertThat(MagicRecipients.parse(Channel.EMAIL, "+550000000001")).isEmpty();
        assertThat(MagicRecipients.parse(Channel.SMS, "always-transient@fail.test")).isEmpty();
    }

    @Test
    void failUntilReprocessFailsTheInitialSendPlusThreeLevels() {
        assertThat(MagicRecipients.FAIL_UNTIL_REPROCESS_ATTEMPTS).isEqualTo(4);
    }

    @ParameterizedTest(name = "{0} {1} passa na validação da API")
    @CsvSource({
            "EMAIL,   always-transient@fail.test",
            "EMAIL,   always-permanent@fail.test",
            "EMAIL,   fail-3-then-ok@fail.test",
            "EMAIL,   fail-99-then-ok@fail.test",
            "EMAIL,   fail-until-reprocess@fail.test",
            "WEBHOOK, https://fail.test/always-transient",
            "WEBHOOK, http://fail.test/always-transient/x",
            "WEBHOOK, https://fail.test/always-permanent",
            "WEBHOOK, https://fail.test/fail-3-then-ok",
            "WEBHOOK, https://fail.test/fail-until-reprocess",
            "SMS,     +550000000001",
            "SMS,     +550000000002",
            "SMS,     +550000000113",
            "SMS,     +550000000123",
            "SMS,     +550000000133",
            "SMS,     +550000000193",
            "SMS,     +550000000004",
    })
    void everyMagicRecipientPassesTheApiValidation(Channel channel, String recipient) {
        assertThat(RecipientRules.isValid(channel, recipient)).isTrue();
        assertThat(MagicRecipients.parse(channel, recipient)).isPresent();
    }

    @ParameterizedTest
    @EnumSource(value = Channel.class)
    void parseNeverThrowsOnGarbage(Channel channel) {
        for (String garbage : new String[] {"@", "@@", "http://", "+", "https://fail.test/%zz", " "}) {
            assertThat(MagicRecipients.parse(channel, garbage)).isEqualTo(Optional.empty());
        }
    }
}
