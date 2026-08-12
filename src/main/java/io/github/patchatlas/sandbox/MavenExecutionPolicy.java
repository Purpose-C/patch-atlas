package io.github.patchatlas.sandbox;

import java.util.Objects;
import java.util.Set;

/** Verification Run 的可信 Maven 执行策略；模型与目标仓库均不能修改。 */
public record MavenExecutionPolicy(String javaVersion, MavenNetworkMode networkMode) {

    public static final String DEFAULT_JAVA_VERSION = "21";
    private static final Set<String> SUPPORTED_JAVA_VERSIONS = Set.of("8", "11", "17", "21");

    public MavenExecutionPolicy {
        Objects.requireNonNull(javaVersion, "javaVersion");
        Objects.requireNonNull(networkMode, "networkMode");
        if (!SUPPORTED_JAVA_VERSIONS.contains(javaVersion)) {
            throw new IllegalArgumentException("javaVersion must be one of 8, 11, 17, 21");
        }
    }

    public String image() {
        return "maven:3.9-eclipse-temurin-" + javaVersion;
    }
}
