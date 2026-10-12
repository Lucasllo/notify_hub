package dev.notifyhub.worker.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/** Header {@code notify-retry-count}: número, string numérica (painel) ou ausente; o resto vai para a DLQ. */
class RetryHeadersTest {

    @Test
    void absentHeaderIsLevelZero() {
        assertThat(RetryHeaders.parse(null)).isZero();
    }

    @Test
    void numbersAreTakenByValue() {
        assertThat(RetryHeaders.parse(2)).isEqualTo(2);
        assertThat(RetryHeaders.parse(3L)).isEqualTo(3);
    }

    @Test
    void numericStringsFromTheManagementUiAreAccepted() {
        assertThat(RetryHeaders.parse("4")).isEqualTo(4);
        assertThat(RetryHeaders.parse(" 5 ")).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "-1", "1.5", "99999999999"})
    void invalidStringsAreRejectedToTheDlq(String header) {
        assertThatThrownBy(() -> RetryHeaders.parse(header))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void negativeFractionalOrHugeNumbersAreRejectedToTheDlq() {
        assertThatThrownBy(() -> RetryHeaders.parse(-1)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> RetryHeaders.parse(1.5)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> RetryHeaders.parse(Long.MAX_VALUE))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void nonNumericTypesAreRejectedToTheDlq() {
        assertThatThrownBy(() -> RetryHeaders.parse(new Object()))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}
