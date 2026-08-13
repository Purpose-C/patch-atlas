package io.github.patchatlas.observability;

/** 未校验的 Pricing Reference 配置字段；全部空白表示未配置。 */
public record PricingFields(
        String provider,
        String model,
        String inputUsdPerMillionTokens,
        String outputUsdPerMillionTokens,
        String effectiveDate,
        String source) {}
