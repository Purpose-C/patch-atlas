package io.github.patchatlas.observability;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** 对 Recorded Model Usage 的估算，不是供应商账单。 */
public record EstimatedModelCost(
        BigDecimal amount, String currency, LocalDate pricingEffectiveDate, String pricingSource) {

    public EstimatedModelCost {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(pricingEffectiveDate, "pricingEffectiveDate");
        Objects.requireNonNull(pricingSource, "pricingSource");
    }
}
