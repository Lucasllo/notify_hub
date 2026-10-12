package dev.notifyhub.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TtlLabelTest {

    @Test
    void wholeSecondsUseTheSecondsSuffix() {
        assertThat(TtlLabel.of(Duration.ofSeconds(5))).isEqualTo("5s");
        assertThat(TtlLabel.of(Duration.ofSeconds(125))).isEqualTo("125s");
        assertThat(TtlLabel.of(Duration.ofSeconds(1))).isEqualTo("1s");
    }

    @Test
    void fractionalSecondsUseTheMillisecondsSuffix() {
        assertThat(TtlLabel.of(Duration.ofMillis(200))).isEqualTo("200ms");
        assertThat(TtlLabel.of(Duration.ofMillis(1500))).isEqualTo("1500ms");
    }

    @Test
    void zeroNegativeAndNullAreRejected() {
        assertThatThrownBy(() -> TtlLabel.of(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TtlLabel.of(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TtlLabel.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
