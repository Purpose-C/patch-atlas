package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Recorded Usage Status 只由 record count 与 attempt count 机械派生。 */
class RecordedUsageStatusTest {

    @Test
    void nullRecordCountIsTrackingUnavailable() {
        assertThat(RecordedUsageStatus.from(null, 0)).isEqualTo(RecordedUsageStatus.TRACKING_UNAVAILABLE);
        assertThat(RecordedUsageStatus.from(null, 3)).isEqualTo(RecordedUsageStatus.TRACKING_UNAVAILABLE);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "0,1", "0,3"})
    void zeroRecordCountIsNoneRecorded(int recordCount, int attemptCount) {
        assertThat(RecordedUsageStatus.from(recordCount, attemptCount))
                .isEqualTo(RecordedUsageStatus.NONE_RECORDED);
    }

    @ParameterizedTest
    @CsvSource({"1,2", "1,3", "2,3"})
    void partialCoverageIsPartiallyRecorded(int recordCount, int attemptCount) {
        assertThat(RecordedUsageStatus.from(recordCount, attemptCount))
                .isEqualTo(RecordedUsageStatus.PARTIALLY_RECORDED);
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,2", "3,3"})
    void equalPositiveCountsAreRecordedForAllAttempts(int recordCount, int attemptCount) {
        assertThat(RecordedUsageStatus.from(recordCount, attemptCount))
                .isEqualTo(RecordedUsageStatus.RECORDED_FOR_ALL_ATTEMPTS);
    }

    @Test
    void rejectsCountAboveAttempts() {
        assertThatThrownBy(() -> RecordedUsageStatus.from(2, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usage record count");
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> RecordedUsageStatus.from(-1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordedUsageStatus.from(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
