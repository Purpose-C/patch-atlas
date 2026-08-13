package io.github.patchatlas.observability;

import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.RecordedUsageStatus;
import io.github.patchatlas.run.RunFailure;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Optional;

/** 预注册封闭 tag 组合的 Run Aggregate Metrics。 */
public final class RunAggregateMeters {

    static final String[] PROVIDERS = {"fake", "openai", "agnes"};
    static final String[] TOKEN_TYPES = {"input", "output", "total"};

    private RunAggregateMeters() {}

    public static void bind(
            MeterRegistry registry, RunAggregateReader reader, Optional<PricingReference> pricing) {
        for (VerificationMode mode : VerificationMode.values()) {
            for (ReplayVerdict verdict : ReplayVerdict.values()) {
                FunctionCounter.builder(
                                "patchatlas.run.completed",
                                reader,
                                r -> r.completedRuns(mode, verdict))
                        .baseUnit("runs")
                        .tags("mode", tag(mode), "verdict", tag(verdict))
                        .register(registry);
            }
            for (FailureStage stage : FailureStage.values()) {
                for (FailureCategory category : FailureCategory.values()) {
                    if (RunFailure.legalPair(stage, category)) {
                        FunctionCounter.builder(
                                        "patchatlas.run.failed",
                                        reader,
                                        r -> r.failedRuns(mode, stage, category))
                                .baseUnit("runs")
                                .tags("mode", tag(mode), "stage", tag(stage), "category", tag(category))
                                .register(registry);
                    }
                }
            }
        }
        for (String provider : PROVIDERS) {
            FunctionCounter.builder(
                            "patchatlas.generation.attempts",
                            reader,
                            r -> r.generationAttempts(provider))
                    .baseUnit("attempts")
                    .tags("provider", provider)
                    .register(registry);
            FunctionCounter.builder(
                            "patchatlas.model.usage.records",
                            reader,
                            r -> r.usageRecords(provider))
                    .baseUnit("records")
                    .tags("provider", provider)
                    .register(registry);
            for (RecordedUsageStatus status : RecordedUsageStatus.values()) {
                Gauge.builder(
                                "patchatlas.model.usage.runs",
                                reader,
                                r -> r.usageRuns(provider, status))
                        .baseUnit("runs")
                        .tags("provider", provider, "status", tag(status))
                        .register(registry);
            }
            for (String type : TOKEN_TYPES) {
                FunctionCounter.builder(
                                "patchatlas.model.tokens",
                                reader,
                                r -> r.tokens(provider, type))
                        .baseUnit("tokens")
                        .tags("provider", provider, "type", type)
                        .register(registry);
            }
        }
        pricing.ifPresent(ref -> Gauge.builder(
                        "patchatlas.model.cost.estimated",
                        reader,
                        r -> estimatedCostUsd(r, ref))
                .baseUnit("usd")
                .tags("provider", ref.provider(), "currency", "usd")
                .register(registry));
    }

    private static double estimatedCostUsd(RunAggregateReader reader, PricingReference pricing) {
        RunAggregateReader.TokenSnapshot tokens =
                reader.tokensForModelSnapshot(pricing.provider(), pricing.model());
        return EstimatedModelCostCalculator.estimate(
                        pricing,
                        pricing.provider(),
                        pricing.model(),
                        tokens.input(),
                        tokens.output(),
                        tokens.total())
                .map(cost -> cost.amount().doubleValue())
                .orElse(0.0);
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
