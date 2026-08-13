package io.github.patchatlas.agent;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 生成器身份：预占额度时写入 Run，同一 Run 内不得变化。 */
public record GeneratorIdentity(String provider, String modelName) {

    public static final int MAX_PROVIDER_CHARS = 32;
    public static final int MAX_MODEL_CHARS = 128;
    private static final Set<String> ALLOWED_PROVIDERS = Set.of("fake", "openai", "agnes");

    public GeneratorIdentity {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelName, "modelName");
        provider = provider.toLowerCase(Locale.ROOT);
        if (!ALLOWED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("provider must be one of " + ALLOWED_PROVIDERS);
        }
        if (modelName.isBlank() || modelName.length() > MAX_MODEL_CHARS) {
            throw new IllegalArgumentException("modelName must be non-blank and <= " + MAX_MODEL_CHARS);
        }
        if (provider.length() > MAX_PROVIDER_CHARS) {
            throw new IllegalArgumentException("provider exceeds limit");
        }
    }

    public static GeneratorIdentity fake(String fixtureModel) {
        return new GeneratorIdentity("fake", fixtureModel);
    }

    public static GeneratorIdentity openai(String modelName) {
        return new GeneratorIdentity("openai", modelName);
    }

    public static GeneratorIdentity agnes(String modelName) {
        return new GeneratorIdentity("agnes", modelName);
    }
}
