package io.github.patchatlas.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.observability.PricingFields;
import io.github.patchatlas.observability.PricingReference;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.RecordedUsageStatus;
import io.github.patchatlas.run.RunDetailView;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ��详情 DTO 向后兼容增加 usage 四态与估算费用。 */
class RunDetailResponseMappingTest {

    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void mapsTrackingUnavailableAndOmitsCostWithoutPricing() {
        RunDetailResponse response = RunDtos.toDetailResponse(
                detail(0, null, null, 0, 0, 0, null), Optional.empty());

        assertThat(response.generation().usageRecordCount()).isNull();
        assertThat(response.generation().usageStatus())
                .isEqualTo(RecordedUsageStatus.TRACKING_UNAVAILABLE.name());
        assertThat(response.generation().estimatedCost()).isNull();
        assertThat(response.generation().attemptCount()).isZero();
        assertThat(response.generation().inputTokens()).isZero();
    }

    @Test
    void mapsFourUsageStatuses() {
        assertThat(status(1, 0)).isEqualTo("NONE_RECORDED");
        assertThat(status(2, 1)).isEqualTo("PARTIALLY_RECORDED");
        assertThat(status(2, 2)).isEqualTo("RECORDED_FOR_ALL_ATTEMPTS");
        assertThat(status(1, null)).isEqualTo("TRACKING_UNAVAILABLE");
    }

    @Test
    void estimatesCostOnlyWhenModelMatches() {
        PricingReference pricing = PricingReference.parse(new PricingFields(
                        "openai", "gpt-4.1-mini", "2.00", "8.00", "2026-08-13", "fixture"))
                .orElseThrow();

        RunDetailResponse matched = RunDtos.toDetailResponse(
                detail(1, "openai", "gpt-4.1-mini", 1_000_000, 0, 1_000_000, 1),
                Optional.of(pricing));
        assertThat(matched.generation().usageStatus()).isEqualTo("RECORDED_FOR_ALL_ATTEMPTS");
        assertThat(matched.generation().estimatedCost()).isNotNull();
        assertThat(matched.generation().estimatedCost().amount()).isEqualTo("2.00000000");
        assertThat(matched.generation().estimatedCost().currency()).isEqualTo("USD");
        assertThat(matched.generation().estimatedCost().pricingEffectiveDate()).isEqualTo("2026-08-13");
        assertThat(matched.generation().estimatedCost().pricingSource()).isEqualTo("fixture");

        RunDetailResponse mismatched = RunDtos.toDetailResponse(
                detail(1, "openai", "other-model", 1_000_000, 0, 1_000_000, 1),
                Optional.of(pricing));
        assertThat(mismatched.generation().estimatedCost()).isNull();
    }

    private static String status(int attempts, Integer records) {
        return RunDtos.toDetailResponse(
                        detail(attempts, "openai", "gpt-4.1-mini", 1, 1, 2, records), Optional.empty())
                .generation()
                .usageStatus();
    }

    private static RunDetailView detail(
            int attempts,
            String provider,
            String model,
            long input,
            long output,
            long total,
            Integer usageRecords) {
        return new RunDetailView(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                VerificationMode.LIVE,
                RunState.GENERATING,
                "case-1",
                NOW,
                NOW,
                null,
                new RunDetailView.InputSummary(
                        "https://github.com/ex/repo.git",
                        null,
                        "t",
                        "b",
                        "a".repeat(40),
                        null,
                        ""),
                new MavenExecutionPolicy("21", MavenNetworkMode.OFFLINE),
                new RunDetailView.GenerationMeta(
                        attempts, provider, model, input, output, total, usageRecords),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }
}
