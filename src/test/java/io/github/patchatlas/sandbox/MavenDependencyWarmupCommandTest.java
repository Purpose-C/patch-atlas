package io.github.patchatlas.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MavenDependencyWarmupCommandTest {

    @Test
    void buildsOnlineTargetedWarmupCommandFromFixedTemplate() {
        MavenDependencyWarmupCommand command =
                new MavenDependencyWarmupCommand(
                        "spring-cloud-openfeign-core",
                        "SpringMvcContractTests#getWithSingleUriParameterShouldNotWarn");

        assertThat(command.networkMode()).isEqualTo(MavenNetworkMode.ONLINE);
        assertThat(command.arguments())
                .isEqualTo(List.of(
                        "mvn",
                        "-B",
                        "-Dmaven.repo.local=/maven-cache/repository",
                        "-pl",
                        "spring-cloud-openfeign-core",
                        "-am",
                        "-Dtest=SpringMvcContractTests#getWithSingleUriParameterShouldNotWarn",
                        "-Dsurefire.failIfNoSpecifiedTests=false",
                        "-Dmaven.test.failure.ignore=true",
                        "test"));
    }
}
