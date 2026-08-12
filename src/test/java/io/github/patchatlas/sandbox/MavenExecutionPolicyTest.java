package io.github.patchatlas.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MavenExecutionPolicyTest {

    @Test
    void mapsEachSupportedJavaVersionToControlledImage() {
        assertThat(new MavenExecutionPolicy("8", MavenNetworkMode.OFFLINE).image())
                .isEqualTo("maven:3.9-eclipse-temurin-8");
        assertThat(new MavenExecutionPolicy("11", MavenNetworkMode.OFFLINE).image())
                .isEqualTo("maven:3.9-eclipse-temurin-11");
        assertThat(new MavenExecutionPolicy("17", MavenNetworkMode.ONLINE).image())
                .isEqualTo("maven:3.9-eclipse-temurin-17");
        assertThat(new MavenExecutionPolicy("21", MavenNetworkMode.OFFLINE).image())
                .isEqualTo("maven:3.9-eclipse-temurin-21");
    }

    @Test
    void rejectsUnsupportedOrMissingJavaVersion() {
        assertThatThrownBy(() -> new MavenExecutionPolicy("22", MavenNetworkMode.OFFLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("javaVersion");
        assertThatThrownBy(() -> new MavenExecutionPolicy(null, MavenNetworkMode.OFFLINE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("javaVersion");
    }
}
