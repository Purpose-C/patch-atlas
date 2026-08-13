package io.github.patchatlas.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code patchatlas.observability.pricing.*}；全部空白表示未配置。 */
@ConfigurationProperties(prefix = "patchatlas.observability.pricing")
public class ObservabilityPricingProperties {

    private String provider = "";
    private String model = "";
    private String inputUsdPerMillionTokens = "";
    private String outputUsdPerMillionTokens = "";
    private String effectiveDate = "";
    private String source = "";

    public PricingFields toFields() {
        return new PricingFields(
                provider,
                model,
                inputUsdPerMillionTokens,
                outputUsdPerMillionTokens,
                effectiveDate,
                source);
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getInputUsdPerMillionTokens() {
        return inputUsdPerMillionTokens;
    }

    public void setInputUsdPerMillionTokens(String inputUsdPerMillionTokens) {
        this.inputUsdPerMillionTokens = inputUsdPerMillionTokens;
    }

    public String getOutputUsdPerMillionTokens() {
        return outputUsdPerMillionTokens;
    }

    public void setOutputUsdPerMillionTokens(String outputUsdPerMillionTokens) {
        this.outputUsdPerMillionTokens = outputUsdPerMillionTokens;
    }

    public String getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(String effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
