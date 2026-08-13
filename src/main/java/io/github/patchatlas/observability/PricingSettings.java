package io.github.patchatlas.observability;

import java.util.Optional;

/** 启动时解析后的价格依据；未配置时为空。 */
public record PricingSettings(Optional<PricingReference> reference) {

    public PricingSettings {
        reference = reference == null ? Optional.empty() : reference;
    }

    public static PricingSettings from(ObservabilityPricingProperties properties) {
        return new PricingSettings(PricingReference.parse(properties.toFields()));
    }
}
