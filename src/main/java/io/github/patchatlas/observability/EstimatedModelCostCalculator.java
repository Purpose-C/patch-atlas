package io.github.patchatlas.observability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** 纯计算器：不读网络、环境变量、Run Store 或模型 adapter。 */
public final class EstimatedModelCostCalculator {

    public static final int AMOUNT_SCALE = 8;
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private EstimatedModelCostCalculator() {}

    public static Optional<EstimatedModelCost> estimate(
            PricingReference pricing,
            String runProvider,
            String runModel,
            long inputTokens,
            long outputTokens,
            long totalTokens) {
        if (pricing == null || runProvider == null || runModel == null) {
            return Optional.empty();
        }
        if (!PricingReference.OPENAI.equals(runProvider) || !PricingReference.OPENAI.equals(pricing.provider())) {
            return Optional.empty();
        }
        if (!pricing.model().equals(runModel)) {
            return Optional.empty();
        }
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        if (totalTokens > 0 && inputTokens == 0 && outputTokens == 0) {
            return Optional.empty();
        }
        BigDecimal amount = pricing.inputUsdPerMillionTokens()
                .multiply(BigDecimal.valueOf(inputTokens))
                .add(pricing.outputUsdPerMillionTokens().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, AMOUNT_SCALE, RoundingMode.HALF_UP);
        return Optional.of(new EstimatedModelCost(
                amount, PricingReference.USD, pricing.effectiveDate(), pricing.source()));
    }
}
