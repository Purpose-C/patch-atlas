package io.github.patchatlas.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** ��费用只由 Pricing Reference 与已记录 token 计算，不是账单。 */
class EstimatedModelCostCalculatorTest {

    private static final PricingReference OPENAI = pricing("openai", "gpt-4.1-mini", "2.00", "8.00");

    @Test
    void computesScale8HalfUpFromRecordedTokens() {
        EstimatedModelCost cost = EstimatedModelCostCalculator.estimate(
                        OPENAI, "openai", "gpt-4.1-mini", 1_000_000, 500_000, 1_500_000)
                .orElseThrow();

        assertThat(cost.amount()).isEqualByComparingTo("6.00000000");
        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(cost.pricingEffectiveDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(cost.pricingSource()).isEqualTo("docs/pricing-fixture");
    }

    @Test
    void roundsHalfUpAtScale8() {
        // 1 × 0.014 / 1_000_000 = 0.000000014 → 0.00000001
        PricingReference down = pricing("openai", "gpt-4.1-mini", "0.014", "0");
        assertThat(EstimatedModelCostCalculator.estimate(down, "openai", "gpt-4.1-mini", 1, 0, 1)
                        .orElseThrow()
                        .amount())
                .isEqualByComparingTo("0.00000001");

        // 1 × 0.015 / 1_000_000 = 0.000000015 → 0.00000002
        PricingReference up = pricing("openai", "gpt-4.1-mini", "0.015", "0");
        assertThat(EstimatedModelCostCalculator.estimate(up, "openai", "gpt-4.1-mini", 1, 0, 1)
                        .orElseThrow()
                        .amount())
                .isEqualByComparingTo("0.00000002");

        PricingReference visible = pricing("openai", "gpt-4.1-mini", "0.15", "0.60");
        EstimatedModelCost tiny = EstimatedModelCostCalculator.estimate(
                        visible, "openai", "gpt-4.1-mini", 1, 1, 2)
                .orElseThrow();
        // (0.15 + 0.60) / 1_000_000 = 0.00000075
        assertThat(tiny.amount()).isEqualByComparingTo("0.00000075");
    }

    @Test
    void missingPricingIsUnavailable() {
        assertThat(EstimatedModelCostCalculator.estimate(
                        null, "openai", "gpt-4.1-mini", 10, 10, 20))
                .isEmpty();
    }

    @Test
    void modelMismatchIsUnavailable() {
        assertThat(EstimatedModelCostCalculator.estimate(
                        OPENAI, "openai", "gpt-4.1", 10, 10, 20))
                .isEmpty();
    }

    @Test
    void fakeProviderNeverHasCost() {
        assertThat(EstimatedModelCostCalculator.estimate(
                        OPENAI, "fake", "gpt-4.1-mini", 10, 10, 20))
                .isEmpty();
    }

    @Test
    void totalWithoutInputOutputDecompositionIsUnavailable() {
        assertThat(EstimatedModelCostCalculator.estimate(
                        OPENAI, "openai", "gpt-4.1-mini", 0, 0, 12))
                .isEmpty();
    }

    @Test
    void oneSidedTokensStillProduceLowerBound() {
        EstimatedModelCost inputOnly = EstimatedModelCostCalculator.estimate(
                        OPENAI, "openai", "gpt-4.1-mini", 1_000_000, 0, 1_000_000)
                .orElseThrow();
        assertThat(inputOnly.amount()).isEqualByComparingTo("2.00000000");

        EstimatedModelCost outputOnly = EstimatedModelCostCalculator.estimate(
                        OPENAI, "openai", "gpt-4.1-mini", 0, 1_000_000, 1_000_000)
                .orElseThrow();
        assertThat(outputOnly.amount()).isEqualByComparingTo("8.00000000");
    }

    @Test
    void pricingReferenceRejectsPartialAndIllegalValues() {
        assertThat(PricingReference.parse(allBlank())).isEmpty();

        assertThatThrownBy(() -> PricingReference.parse(new PricingFields(
                        "openai", "", "1", "1", "2026-08-13", "src")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pricing");

        assertThatThrownBy(() -> PricingReference.parse(new PricingFields(
                        "anthropic", "claude", "1", "1", "2026-08-13", "src")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai");

        assertThatThrownBy(() -> PricingReference.parse(new PricingFields(
                        "openai", "gpt-4.1-mini", "-1", "1", "2026-08-13", "src")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PricingReference.parse(new PricingFields(
                        "openai", "gpt-4.1-mini", "1", "1", "13-08-2026", "src")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PricingReference.parse(new PricingFields(
                        "openai", "gpt-4.1-mini", "1", "1", "2026-08-13", "x".repeat(513))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completePricingParsesImmutableReference() {
        PricingReference parsed = PricingReference.parse(new PricingFields(
                        "openai", "gpt-4.1-mini", "2.5", "10", "2026-08-13", "fixture"))
                .orElseThrow();
        assertThat(parsed.provider()).isEqualTo("openai");
        assertThat(parsed.model()).isEqualTo("gpt-4.1-mini");
        assertThat(parsed.inputUsdPerMillionTokens()).isEqualByComparingTo("2.5");
        assertThat(parsed.outputUsdPerMillionTokens()).isEqualByComparingTo("10");
        assertThat(parsed.effectiveDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(parsed.source()).isEqualTo("fixture");
    }

    private static PricingFields allBlank() {
        return new PricingFields("", "", "", "", "", "");
    }

    private static PricingReference pricing(
            String provider, String model, String input, String output) {
        return PricingReference.parse(new PricingFields(
                        provider, model, input, output, "2026-08-13", "docs/pricing-fixture"))
                .orElseThrow();
    }
}
