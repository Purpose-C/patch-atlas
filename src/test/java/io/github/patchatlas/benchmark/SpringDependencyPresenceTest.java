package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpringDependencyPresenceTest {

    @Test
    void detectsSpringFrameworkGroupIdInsideADependency() {
        var scan = SpringDependencyPresence.scan(
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

        assertThat(scan.present()).isTrue();
        assertThat(scan.matchingGroupIds()).containsExactly("org.springframework");
    }

    @Test
    void detectsSpringBootGroupIdBecauseItContainsTheFrameworkMarker() {
        var scan = SpringDependencyPresence.scan(
                """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertThat(scan.present()).isTrue();
        assertThat(scan.matchingGroupIds()).containsExactly("org.springframework.boot");
    }

    @Test
    void detectsSpringDependencyInAChildModulePom() {
        var scan = SpringDependencyPresence.scan(List.of(
                "<project><artifactId>root</artifactId></project>",
                """
                <project>
                  <artifactId>module</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.cloud</groupId>
                      <artifactId>spring-cloud-context</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """));

        assertThat(scan.present()).isTrue();
        assertThat(scan.matchingGroupIds()).containsExactly("org.springframework.cloud");
    }

    @Test
    void ignoresSpringBootParentWithoutASpringDependency() {
        var scan = SpringDependencyPresence.scan(
                """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.0</version>
                  </parent>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.commons</groupId>
                      <artifactId>commons-lang3</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertThat(scan.present()).isFalse();
        assertThat(scan.matchingGroupIds()).isEmpty();
    }

    @Test
    void ignoresSpringMavenPluginCoordinates() {
        var scan = SpringDependencyPresence.scan(
                """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-maven-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(scan.present()).isFalse();
    }

    @Test
    void ignoresLookalikeGroupIdsAndSpringLikeArtifactNames() {
        var scan = SpringDependencyPresence.scan(
                """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springdoc</groupId>
                      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>spring-core</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertThat(scan.present()).isFalse();
    }

    @Test
    void countsImportedBomDependencies() {
        var scan = SpringDependencyPresence.scan(
                """
                <project>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-dependencies</artifactId>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        assertThat(scan.present()).isTrue();
        assertThat(scan.matchingGroupIds()).containsExactly("org.springframework.boot");
    }

    @Test
    void doesNotUseARepositoryNameToDecidePresence() {
        var scan = SpringDependencyPresence.scan(
                "<project><artifactId>spring-projects-spring-retry</artifactId></project>");

        assertThat(scan.present()).isFalse();
    }
}
