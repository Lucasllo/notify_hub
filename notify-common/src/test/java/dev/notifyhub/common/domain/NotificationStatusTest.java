package dev.notifyhub.common.domain;

import static dev.notifyhub.common.domain.NotificationStatus.DEAD_LETTERED;
import static dev.notifyhub.common.domain.NotificationStatus.FAILED;
import static dev.notifyhub.common.domain.NotificationStatus.PENDING;
import static dev.notifyhub.common.domain.NotificationStatus.PROCESSING;
import static dev.notifyhub.common.domain.NotificationStatus.PUBLISH_FAILED;
import static dev.notifyhub.common.domain.NotificationStatus.RETRYING;
import static dev.notifyhub.common.domain.NotificationStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NotificationStatusTest {

    @Test
    void hasExactlyTheSevenStatuses() {
        assertThat(NotificationStatus.values())
                .containsExactly(PENDING, PROCESSING, SENT, RETRYING, FAILED, DEAD_LETTERED, PUBLISH_FAILED);
    }

    @Test
    void everyStatusHasAnEntryInTheTransitionTable() {
        // canTransitionTo must not blow up for any pair (no missing table entry)
        Arrays.stream(NotificationStatus.values()).forEach(from ->
                Arrays.stream(NotificationStatus.values()).forEach(from::canTransitionTo));
    }

    @Test
    void sentIsTerminal() {
        Arrays.stream(NotificationStatus.values())
                .forEach(target -> assertThat(SENT.canTransitionTo(target)).as("SENT -> %s", target).isFalse());
    }

    @Test
    void pendingCanBeClaimedOrMarkedPublishFailedButNotSentDirectly() {
        assertThat(PENDING.canTransitionTo(PROCESSING)).isTrue();
        assertThat(PENDING.canTransitionTo(PUBLISH_FAILED)).isTrue();
        assertThat(PENDING.canTransitionTo(SENT)).isFalse();
    }

    @Test
    void reprocessingAndReclaimTransitionsArePartOfTheContract() {
        assertThat(PROCESSING.canTransitionTo(PROCESSING)).isTrue();
        assertThat(FAILED.canTransitionTo(PENDING)).isTrue();
        assertThat(DEAD_LETTERED.canTransitionTo(PENDING)).isTrue();
        assertThat(PUBLISH_FAILED.canTransitionTo(PENDING)).isTrue();
    }

    @Test
    void sourcesOfProcessingAreDerivedFromTheTable() {
        assertThat(NotificationStatus.sourcesOf(PROCESSING))
                .containsExactlyInAnyOrder(PENDING, PUBLISH_FAILED, PROCESSING, RETRYING);
    }

    @Test
    void claimableForDeliveryExcludesProcessing() {
        assertThat(NotificationStatus.claimableForDelivery())
                .containsExactlyInAnyOrder(PENDING, RETRYING, PUBLISH_FAILED);
    }

    @Test
    void returnedSetsAreImmutable() {
        assertThatThrownBy(() -> NotificationStatus.claimableForDelivery().add(SENT))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> NotificationStatus.sourcesOf(PENDING).add(SENT))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
