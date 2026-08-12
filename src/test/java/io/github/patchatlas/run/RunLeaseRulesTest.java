package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 租约 / 版本 / 恢复次数领域规则（��无数据库）。
 */
class RunLeaseRulesTest {

    @Test
    void queuedMustNotHoldLease() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RunLeaseRules.requireLeaseShape(
                        RunState.QUEUED, lease(Instant.now().plusSeconds(30))));
        assertThatCode(() -> RunLeaseRules.requireLeaseShape(RunState.QUEUED, null))
                .doesNotThrowAnyException();
    }

    @Test
    void generatingAndReplayingMustHoldLease() {
        RunLease lease = lease(Instant.parse("2026-08-12T00:00:30Z"));
        assertThatCode(() -> RunLeaseRules.requireLeaseShape(RunState.GENERATING, lease))
                .doesNotThrowAnyException();
        assertThatCode(() -> RunLeaseRules.requireLeaseShape(RunState.REPLAYING, lease))
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RunLeaseRules.requireLeaseShape(RunState.GENERATING, null));
    }

    @Test
    void terminalMustClearLease() {
        assertThatCode(() -> RunLeaseRules.requireLeaseShape(RunState.COMPLETED, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> RunLeaseRules.requireLeaseShape(RunState.FAILED, null))
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RunLeaseRules.requireLeaseShape(
                        RunState.COMPLETED, lease(Instant.now().plusSeconds(10))));
    }

    @Test
    void versionMustBeNonNegativeAndMonotonicOnWrite() {
        assertThat(RunLeaseRules.nextVersion(0)).isEqualTo(1);
        assertThat(RunLeaseRules.nextVersion(41)).isEqualTo(42);
        assertThatIllegalArgumentException().isThrownBy(() -> RunLeaseRules.nextVersion(-1));
    }

    @Test
    void recoveryCountCappedAtThreeSuccessfulReclaims() {
        assertThat(RunLeaseRules.MAX_RECOVERY_COUNT).isEqualTo(3);
        assertThat(RunLeaseRules.canReclaim(0)).isTrue();
        assertThat(RunLeaseRules.canReclaim(2)).isTrue();
        assertThat(RunLeaseRules.canReclaim(3)).isFalse();

        assertThat(RunLeaseRules.nextRecoveryCount(0)).isEqualTo(1);
        assertThat(RunLeaseRules.nextRecoveryCount(2)).isEqualTo(3);
        assertThatIllegalStateException().isThrownBy(() -> RunLeaseRules.nextRecoveryCount(3));
    }

    @Test
    void leaseExpiryComparedAgainstDatabaseClockInstant() {
        Instant now = Instant.parse("2026-08-12T12:00:00Z");
        RunLease active = lease(now.plusSeconds(60));
        RunLease expired = lease(now.minusSeconds(1));

        assertThat(RunLeaseRules.isExpired(active, now)).isFalse();
        assertThat(RunLeaseRules.isExpired(expired, now)).isTrue();
        // 截止点等于 now 视为已过期（可被接管）
        assertThat(RunLeaseRules.isExpired(lease(now), now)).isTrue();
    }

    @Test
    void leaseRequiresNonBlankOwnerAndPositiveExpiry() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RunLease(UUID.randomUUID(), " ", Instant.now().plusSeconds(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RunLease(null, "worker-1", Instant.now().plusSeconds(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RunLease(UUID.randomUUID(), "worker-1", null));
    }

    private static RunLease lease(Instant expiresAt) {
        return new RunLease(UUID.randomUUID(), "worker-1", expiresAt);
    }
}
