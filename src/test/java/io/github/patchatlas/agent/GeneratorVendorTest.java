package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** vendor 字符串到 provider 身份的映射。provider 会进入证据与指标，不得张冠李戴。 */
class GeneratorVendorTest {

    @Test
    void mapsEachSupportedVendorToItsOwnProvider() {
        assertThat(GeneratorConfiguration.identityForVendor("openai", "gpt-x").provider())
                .isEqualTo("openai");
        assertThat(GeneratorConfiguration.identityForVendor("agnes", "agnes-2.5-flash").provider())
                .isEqualTo("agnes");
        assertThat(GeneratorConfiguration.identityForVendor("ollama", "glm-5.2").provider())
                .isEqualTo("ollama");
    }

    @Test
    void defaultsToOpenAiOnlyWhenVendorAbsent() {
        assertThat(GeneratorConfiguration.identityForVendor(null, "m").provider()).isEqualTo("openai");
        assertThat(GeneratorConfiguration.identityForVendor("  ", "m").provider()).isEqualTo("openai");
    }

    @Test
    void rejectsUnknownVendorInsteadOfSilentlyRecordingOpenAi() {
        assertThatThrownBy(() -> GeneratorConfiguration.identityForVendor("mystery", "m"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mystery");
    }

    @Test
    void ollamaModelIsCarriedThroughUnchanged() {
        assertThat(GeneratorConfiguration.identityForVendor("OLLAMA", "glm-5.2").modelName())
                .isEqualTo("glm-5.2");
    }

    @Test
    void springAiGeneratorAcceptsOllamaWithoutRecordingItAsOpenAi() {
        org.springframework.ai.chat.model.ChatModel unused = prompt -> {
            throw new AssertionError("constructor must not call the model");
        };
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.ollama("glm-5.2"), unused);
        assertThat(generator).isNotNull();
    }
}
