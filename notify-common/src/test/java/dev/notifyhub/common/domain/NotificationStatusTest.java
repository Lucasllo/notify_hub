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
    void reprocessResetAcceptsNonTerminalStatusesWhoseMessageIsInTheDlq() {
        // D-01/CR-01: o reset do reprocessamento retoma documentos não terminais com a mensagem na DLQ
        assertThat(PENDING.canTransitionTo(PENDING)).isTrue();
        assertThat(RETRYING.canTransitionTo(PENDING)).isTrue();
        assertThat(PROCESSING.canTransitionTo(PENDING)).isTrue();
    }

    @Test
    void sourcesOfPendingAreDerivedFromTheTable() {
        assertThat(NotificationStatus.sourcesOf(PENDING))
                .containsExactlyInAnyOrder(PENDING, PUBLISH_FAILED, PROCESSING, RETRYING, FAILED, DEAD_LETTERED);
    }

    @Test
    void sourcesOfProcessingAreDerivedFromTheTable() {
        assertThat(NotificationStatus.sourcesOf(PROCESSING))
                .containsExactlyInAnyOrder(PENDING, PUBLISH_FAILED, PROCESSING, RETRYING);
    }

    @Test
    void retryingCanBeClaimedOrDeadLetteredWhenTheRepublishFails() {
        assertThat(RETRYING.canTransitionTo(PROCESSING)).isTrue();
        assertThat(RETRYING.canTransitionTo(DEAD_LETTERED)).isTrue();
        assertThat(RETRYING.canTransitionTo(FAILED)).isFalse();
        assertThat(RETRYING.canTransitionTo(SENT)).isFalse();
    }

    @Test
    void sourcesOfDeadLetteredAreProcessingAndRetrying() {
        assertThat(NotificationStatus.sourcesOf(DEAD_LETTERED)).containsExactlyInAnyOrder(PROCESSING, RETRYING);
    }

    @Test
    void sourcesOfFailedAreOnlyProcessing() {
        assertThat(NotificationStatus.sourcesOf(FAILED)).containsExactly(PROCESSING);
    }

    @Test
    void claimableForDeliveryExcludesProcessing() {
        assertThat(NotificationStatus.claimableForDelivery())
                .containsExactlyInAnyOrder(PENDING, RETRYING, PUBLISH_FAILED);
    }

    @Test
    void reclaimableWhenStuckIsOnlyProcessingDerivedFromTheTable() {
        assertThat(NotificationStatus.reclaimableWhenStuck()).containsExactly(PROCESSING);
        assertThat(NotificationStatus.reclaimableWhenStuck())
                .allMatch(status -> status.canTransitionTo(PROCESSING));
        assertThat(NotificationStatus.reclaimableWhenStuck())
                .doesNotContainAnyElementsOf(NotificationStatus.claimableForDelivery());
    }

    @Test
    void returnedSetsAreImmutable() {
        assertThatThrownBy(() -> NotificationStatus.reclaimableWhenStuck().add(SENT))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> NotificationStatus.claimableForDelivery().add(SENT))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> NotificationStatus.sourcesOf(PENDING).add(SENT))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
