package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryStaticInspectorTest {

    @TempDir
    Path workspace;

    @Test
    void readsJavaCompatibilitySnapshotDependenciesAndLicenseWithoutBuilding() throws Exception {
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <project>
                  <version>1.0-SNAPSHOT</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <dependencies>
                    <dependency><groupId>x</groupId><artifactId>y</artifactId><version>2-SNAPSHOT</version></dependency>
                  </dependencies>
                </project>
                """);
        Files.writeString(
                workspace.resolve("LICENSE"),
                "Apache License\nVersion 2.0, January 2004\n");

        var facts = new RepositoryStaticInspector().inspect(workspace);

        assertThat(facts.supportedJavaVersions()).isEqualTo(Set.of(17, 21));
        assertThat(facts.snapshotDependencyPresent()).isTrue();
        assertThat(facts.licenseSpdx()).contains("Apache-2.0");
        assertThat(facts.springDependencyPresent()).isFalse();
    }

    @Test
    void javaTwentyOneDeclarationOnlyAllowsTwentyOne() throws Exception {
        Files.writeString(
                workspace.resolve("pom.xml"),
                "<project><properties><java.version>21</java.version></properties></project>");
        Files.writeString(workspace.resolve("LICENSE.md"), "MIT License\nPermission is hereby granted");

        var facts = new RepositoryStaticInspector().inspect(workspace);

        assertThat(facts.supportedJavaVersions()).containsExactly(21);
        assertThat(facts.snapshotDependencyPresent()).isFalse();
        assertThat(facts.licenseSpdx()).contains("MIT");
    }

    @Test
    void rejectsExplicitJavaAboveSupportedRangeAndUnknownLicense() throws Exception {
        Files.writeString(
                workspace.resolve("pom.xml"),
                "<project><properties><maven.compiler.source>22</maven.compiler.source></properties></project>");
        Files.writeString(workspace.resolve("COPYING"), "custom terms");

        var facts = new RepositoryStaticInspector().inspect(workspace);

        assertThat(facts.supportedJavaVersions()).isEmpty();
        assertThat(facts.licenseSpdx()).isEmpty();
        assertThat(facts.springDependencyPresent()).isFalse();
    }

    @Test
    void recordsSpringPresenceFromAModulePomDependencyWithoutReadingFixes() throws Exception {
        Files.writeString(
                workspace.resolve("pom.xml"),
                "<project><modules><module>app</module></modules></project>");
        Path module = Files.createDirectories(workspace.resolve("app"));
        Files.writeString(
                module.resolve("pom.xml"),
                """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework</groupId>
                      <artifactId>spring-context</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Files.writeString(workspace.resolve("LICENSE"), "MIT License\nPermission is hereby granted");

        var facts = new RepositoryStaticInspector().inspect(workspace);

        assertThat(facts.inspectionComplete()).isTrue();
        assertThat(facts.springDependencyPresent()).isTrue();
    }
}
