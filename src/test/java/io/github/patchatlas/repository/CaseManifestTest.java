package io.github.patchatlas.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CaseManifestTest {

    @Test
    void generatorContextDoesNotExposeOracleAccessors() {
        Set<String> accessors = Arrays.stream(CaseManifest.GeneratorContext.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(accessors)
                .doesNotContain("fixedRevision", "knownTriggerTest", "oracleData")
                .contains("caseId", "repositoryUrl", "buggyRevision");
    }

    @Test
    void scof1326ManifestKeepsOracleOnlyOnOracleSide() {
        CaseManifest manifest = new CaseManifest(
                new CaseManifest.GeneratorContext(
                        "scof-1326",
                        "https://github.com/spring-cloud/spring-cloud-openfeign",
                        "Apache-2.0",
                        "https://github.com/spring-cloud/spring-cloud-openfeign/issues/1326",
                        "3f6cd2eb9b5a9675a3b5fd0a0987ad8cfc3e8398",
                        "spring-cloud-openfeign-core",
                        "17"),
                new CaseManifest.OracleData(
                        "a91d8f565ed3682b9bc363f9f36745d30957c09d",
                        "SpringMvcContractTests#getWithSingleUriParameterShouldNotWarn"));

        CaseManifest.GeneratorContext generator = manifest.generatorContext();
        assertThat(generator.buggyRevision()).startsWith("3f6cd2eb");
        assertThat(manifest.oracleData().fixedRevision()).startsWith("a91d8f56");
    }
}
