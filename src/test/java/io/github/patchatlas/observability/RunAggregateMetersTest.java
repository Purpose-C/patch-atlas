package io.github.patchatlas.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.RecordedUsageStatus;
import io.github.patchatlas.run.RunFailure;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 预注册封闭 tag，FunctionCounter 从只读聚合读取。 */
class RunAggregateMetersTest {

    @Test
    void registersEveryClosedCombinationEvenWhenZero() {
        ScriptedRunAggregateReader reader = new ScriptedRunAggregateReader();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RunAggregateMeters.bind(registry, reader, java.util.Optional.empty());

        assertThat(names(registry))
                .containsExactlyInAnyOrder(
                        "patchatlas.run.completed",
                        "patchatlas.run.failed",
                        "patchatlas.generation.attempts",
                        "patchatlas.model.usage.records",
                        "patchatlas.model.usage.runs",
                        "patchatlas.model.tokens");
        assertThat(names(registry)).doesNotContain("patchatlas.model.cost.estimated");

        assertThat(tagSets(registry, "patchatlas.run.completed", "mode", "verdict"))
                .isEqualTo(expectedCompletedTags());
        assertThat(tagSets(registry, "patchatlas.run.failed", "mode", "stage", "category"))
                .isEqualTo(expectedFailedTags());
        assertThat(tagSets(registry, "patchatlas.generation.attempts", "provider"))
                .containsExactlyInAnyOrder(Set.of("fake"), Set.of("openai"), Set.of("agnes"));
        assertThat(tagSets(registry, "patchatlas.model.usage.records", "provider"))
                .containsExactlyInAnyOrder(Set.of("fake"), Set.of("openai"), Set.of("agnes"));
        assertThat(tagSets(registry, "patchatlas.model.usage.runs", "provider", "status"))
                .isEqualTo(expectedUsageRunTags());
        assertThat(tagSets(registry, "patchatlas.model.tokens", "provider", "type"))
                .isEqualTo(expectedTokenTags());

        FunctionCounter zero = registry
                .find("patchatlas.run.completed")
                .tags("mode", "live", "verdict", "valid_reproduction")
                .functionCounter();
        assertThat(zero).isNotNull();
        assertThat(zero.count()).isZero();
        assertThat(zero.getId().getBaseUnit()).isEqualTo("runs");
    }

    @Test
    void functionCountersReadCurrentAggregatesAndRejectForbiddenTags() {
        ScriptedRunAggregateReader reader = new ScriptedRunAggregateReader();
        reader.completed(VerificationMode.LIVE, ReplayVerdict.VALID_REPRODUCTION, 3);
        reader.failed(
                VerificationMode.HISTORICAL,
                FailureStage.GENERATION,
                FailureCategory.GENERATION_EXHAUSTED,
                2);
        reader.attempts("openai", 7);
        reader.usageRecords("openai", 4);
        reader.usageRuns("openai", RecordedUsageStatus.PARTIALLY_RECORDED, 1);
        reader.tokens("openai", "input", 100);
        reader.tokens("openai", "output", 20);
        reader.tokens("openai", "total", 120);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RunAggregateMeters.bind(registry, reader, java.util.Optional.empty());

        assertThat(counter(registry, "patchatlas.run.completed", "mode", "live", "verdict", "valid_reproduction"))
                .isEqualTo(3);
        assertThat(counter(
                        registry,
                        "patchatlas.run.failed",
                        "mode",
                        "historical",
                        "stage",
                        "generation",
                        "category",
                        "generation_exhausted"))
                .isEqualTo(2);
        assertThat(counter(registry, "patchatlas.generation.attempts", "provider", "openai"))
                .isEqualTo(7);
        assertThat(counter(registry, "patchatlas.model.usage.records", "provider", "openai"))
                .isEqualTo(4);
        assertThat(gaugeValue(
                        registry,
                        "patchatlas.model.usage.runs",
                        "provider",
                        "openai",
                        "status",
                        "partially_recorded"))
                .isEqualTo(1);
        assertThat(counter(registry, "patchatlas.model.tokens", "provider", "openai", "type", "input"))
                .isEqualTo(100);

        assertThat(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream()).map(t -> t.getKey()))
                .doesNotContain("run_id", "model", "repository", "issue", "revision");
    }

    @Test
    void registersCostGaugeOnlyWhenPricingExists() {
        ScriptedRunAggregateReader reader = new ScriptedRunAggregateReader();
        reader.tokensForModelSnapshot("openai", "gpt-4.1-mini", 1_000_000, 500_000, 1_500_000);
        PricingReference pricing = PricingReference.parse(new PricingFields(
                        "openai", "gpt-4.1-mini", "2.00", "8.00", "2026-08-13", "fixture"))
                .orElseThrow();

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RunAggregateMeters.bind(registry, reader, java.util.Optional.of(pricing));

        Gauge gauge = registry
                .find("patchatlas.model.cost.estimated")
                .tags("provider", "openai", "currency", "usd")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(6.0);
    }

    @Test
    void aggregateReadFailureDoesNotReturnZero() {
        ScriptedRunAggregateReader reader = new ScriptedRunAggregateReader();
        reader.failReads();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RunAggregateMeters.bind(registry, reader, java.util.Optional.empty());

        assertThatThrownBy(() -> counter(
                        registry, "patchatlas.run.completed", "mode", "live", "verdict", "valid_reproduction"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aggregate");
    }

    @Test
    void catalogTracksCurrentEnumsSoNewConstantsForceAnUpdate() {
        assertThat(VerificationMode.values()).containsExactly(VerificationMode.HISTORICAL, VerificationMode.LIVE);
        assertThat(ReplayVerdict.values())
                .containsExactly(
                        ReplayVerdict.VALID_REPRODUCTION,
                        ReplayVerdict.REPRODUCTION_CANDIDATE,
                        ReplayVerdict.FAILED_ON_BOTH_COMMITS,
                        ReplayVerdict.NOT_REPRODUCED,
                        ReplayVerdict.INCONCLUSIVE);
        assertThat(FailureStage.values())
                .containsExactly(
                        FailureStage.GENERATION,
                        FailureStage.PATCH_GATE,
                        FailureStage.WORKSPACE,
                        FailureStage.REPLAY,
                        FailureStage.RECOVERY);
        assertThat(FailureCategory.values())
                .containsExactly(
                        FailureCategory.GENERATION_FAILURE,
                        FailureCategory.GENERATION_EXHAUSTED,
                        FailureCategory.MODEL_CONFIGURATION_ERROR,
                        FailureCategory.MODEL_AUTHENTICATION_ERROR,
                        FailureCategory.MODEL_UNAVAILABLE,
                        FailureCategory.MODEL_REFUSED,
                        FailureCategory.PATCH_REJECTED,
                        FailureCategory.WORKSPACE_UNSAFE,
                        FailureCategory.WORKSPACE_ERROR,
                        FailureCategory.REPLAY_SYSTEM_ERROR,
                        FailureCategory.RECOVERY_EXHAUSTED);
        assertThat(RecordedUsageStatus.values())
                .containsExactly(
                        RecordedUsageStatus.TRACKING_UNAVAILABLE,
                        RecordedUsageStatus.NONE_RECORDED,
                        RecordedUsageStatus.PARTIALLY_RECORDED,
                        RecordedUsageStatus.RECORDED_FOR_ALL_ATTEMPTS);

        int legalPairs = 0;
        for (FailureStage stage : FailureStage.values()) {
            for (FailureCategory category : FailureCategory.values()) {
                if (RunFailure.legalPair(stage, category)) {
                    legalPairs++;
                }
            }
        }
        assertThat(legalPairs).isEqualTo(12);
        assertThat(expectedFailedTags()).hasSize(VerificationMode.values().length * legalPairs);
    }

    private static Set<String> names(SimpleMeterRegistry registry) {
        Set<String> names = new HashSet<>();
        for (Meter meter : registry.getMeters()) {
            names.add(meter.getId().getName());
        }
        return names;
    }

    private static Set<Set<String>> tagSets(SimpleMeterRegistry registry, String name, String... keys) {
        Set<Set<String>> sets = new HashSet<>();
        for (Meter meter : registry.getMeters()) {
            if (!name.equals(meter.getId().getName())) {
                continue;
            }
            Set<String> values = new HashSet<>();
            for (String key : keys) {
                values.add(meter.getId().getTag(key));
            }
            sets.add(values);
        }
        return sets;
    }

    private static Set<Set<String>> expectedCompletedTags() {
        Set<Set<String>> sets = new HashSet<>();
        for (VerificationMode mode : VerificationMode.values()) {
            for (ReplayVerdict verdict : ReplayVerdict.values()) {
                sets.add(Set.of(lower(mode.name()), lower(verdict.name())));
            }
        }
        return sets;
    }

    private static Set<Set<String>> expectedFailedTags() {
        Set<Set<String>> sets = new HashSet<>();
        for (VerificationMode mode : VerificationMode.values()) {
            for (FailureStage stage : FailureStage.values()) {
                for (FailureCategory category : FailureCategory.values()) {
                    if (RunFailure.legalPair(stage, category)) {
                        sets.add(Set.of(lower(mode.name()), lower(stage.name()), lower(category.name())));
                    }
                }
            }
        }
        return sets;
    }

    private static Set<Set<String>> expectedUsageRunTags() {
        Set<Set<String>> sets = new HashSet<>();
        for (String provider : new String[] {"fake", "openai", "agnes"}) {
            for (RecordedUsageStatus status : RecordedUsageStatus.values()) {
                sets.add(Set.of(provider, lower(status.name())));
            }
        }
        return sets;
    }

    private static Set<Set<String>> expectedTokenTags() {
        Set<Set<String>> sets = new HashSet<>();
        for (String provider : new String[] {"fake", "openai", "agnes"}) {
            for (String type : new String[] {"input", "output", "total"}) {
                sets.add(Set.of(provider, type));
            }
        }
        return sets;
    }

    private static double counter(SimpleMeterRegistry registry, String name, String... tags) {
        FunctionCounter counter = registry.find(name).tags(tags).functionCounter();
        assertThat(counter).isNotNull();
        return counter.count();
    }

    private static double gaugeValue(SimpleMeterRegistry registry, String name, String... tags) {
        Gauge gauge = registry.find(name).tags(tags).gauge();
        assertThat(gauge).isNotNull();
        return gauge.value();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
