package io.github.patchatlas.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MavenTestCommandTest {

    @Test
    void buildsOfflineModuleTestFromFixedTemplate() {
        MavenTestCommand command = new MavenTestCommand(
                "spring-cloud-openfeign-core",
                "SpringMvcContractTests#getWithSingleUriParameterShouldNotWarn",
                MavenNetworkMode.OFFLINE);

        assertThat(command.arguments())
                .isEqualTo(List.of(
                        "mvn",
                        "-B",
                        "-Dmaven.repo.local=/maven-cache/repository",
                        "-o",
                        "-pl",
                        "spring-cloud-openfeign-core",
                        "-am",
                        "-Dtest=SpringMvcContractTests#getWithSingleUriParameterShouldNotWarn",
                        "test"));
    }

    @Test
    void omitsModuleSelectorForSingleModuleRepository() {
        MavenTestCommand command =
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.ONLINE);

        assertThat(command.arguments())
                .isEqualTo(List.of(
                        "mvn",
                        "-B",
                        "-Dmaven.repo.local=/maven-cache/repository",
                        "-Dtest=fixtures.StringUtilsTest",
                        "test"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../core", "/core", "core;rm", "core//sub", ".", "core module"})
    void rejectsUnsafeModulePath(String modulePath) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MavenTestCommand(
                        modulePath, "ExampleTest", MavenNetworkMode.OFFLINE));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../ExampleTest",
                "ExampleTest;rm",
                "-DskipTests",
                "ExampleTest#method()",
                "ExampleTest method"
            })
    void rejectsUnsafeTestSelector(String testSelector) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MavenTestCommand("", testSelector, MavenNetworkMode.OFFLINE));
    }

    @Test
    void rejectsUnboundedModuleAndTestInputs() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MavenTestCommand(
                        "module/".repeat(100), "ExampleTest", MavenNetworkMode.OFFLINE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MavenTestCommand(
                        "", "A".repeat(257), MavenNetworkMode.OFFLINE));
    }
}
