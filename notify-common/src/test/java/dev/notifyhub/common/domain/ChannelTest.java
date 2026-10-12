package dev.notifyhub.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChannelTest {

    @Test
    void emailConvertsToLowercaseQueueAndRoutingNames() {
        Channel ch = Channel.EMAIL;

        assertThat(ch.id()).isEqualTo("email");
        assertThat(ch.routingKey()).isEqualTo("notification.email");
        assertThat(ch.queue()).isEqualTo("notify.email");
        assertThat(ch.dlq()).isEqualTo("notify.email.dlq");
        assertThat(ch.dlqKey()).isEqualTo("email.dlq");
        assertThat(ch.retryQueue(Duration.ofSeconds(5))).isEqualTo("notify.email.retry.5s");
        assertThat(ch.retryKey(Duration.ofSeconds(5))).isEqualTo("email.retry.5s");
    }

    @ParameterizedTest
    @CsvSource({"SMS, sms", "WEBHOOK, webhook", "EMAIL, email"})
    void everyChannelFollowsTheSameNamingPattern(Channel ch, String id) {
        assertThat(ch.id()).isEqualTo(id);
        assertThat(ch.routingKey()).isEqualTo("notification." + id);
        assertThat(ch.queue()).isEqualTo("notify." + id);
        assertThat(ch.dlq()).isEqualTo("notify." + id + ".dlq");
        assertThat(ch.dlqKey()).isEqualTo(id + ".dlq");
        assertThat(ch.retryQueue(Duration.ofSeconds(25))).isEqualTo("notify." + id + ".retry.25s");
        assertThat(ch.retryKey(Duration.ofSeconds(25))).isEqualTo(id + ".retry.25s");
    }

    @Test
    void retryNamesUseTheTtlLabelNotAnOrdinal() {
        assertThat(Channel.SMS.retryQueue(Duration.ofMillis(200))).isEqualTo("notify.sms.retry.200ms");
        assertThat(Channel.WEBHOOK.retryKey(Duration.ofSeconds(125))).isEqualTo("webhook.retry.125s");
    }

    @Test
    void publicNameStaysUppercase() {
        assertThat(Channel.valueOf("EMAIL")).isSameAs(Channel.EMAIL);
        assertThat(Channel.WEBHOOK.name()).isEqualTo("WEBHOOK");
    }
}
