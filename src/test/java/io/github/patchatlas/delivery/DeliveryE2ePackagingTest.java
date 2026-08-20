package io.github.patchatlas.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeliveryE2ePackagingTest {

    @Test
    void ciRunsChromiumE2eWithoutContinueOnErrorOrSkip() throws IOException {
        String ci = Files.readString(Path.of(".github/workflows/ci.yml"));
        assertThat(ci).contains("npm run e2e");
        assertThat(ci).contains("playwright install --with-deps chromium");
        assertThat(ci).contains("./scripts/up.sh");
        assertThat(ci).doesNotContain("continue-on-error");
        assertThat(ci).doesNotContain("playwright install --dry-run");
        int e2eJob = ci.indexOf("\n  e2e:\n");
        assertThat(e2eJob).isGreaterThanOrEqualTo(0);
        String e2eBlock = ci.substring(e2eJob);
        assertThat(e2eBlock).doesNotContain("if: false");
    }

    @Test
    void playwrightConfigIsChromiumOnlyWithNoRetries() throws IOException {
        String config = Files.readString(Path.of("frontend/playwright.config.ts"));
        assertThat(config).contains("retries: 0");
        assertThat(config).contains("devices['Desktop Chrome']");
        assertThat(config).doesNotContain("firefox");
        assertThat(config).doesNotContain("webkit");
        assertThat(config).doesNotContain("webServer");
    }

    @Test
    void e2eSuiteHasAtMostTwelveTestsAndKeepsVitestSeparate() throws IOException {
        int tests = 0;
        try (var paths = Files.list(Path.of("frontend/e2e"))) {
            for (Path path : paths.toList()) {
                if (!path.getFileName().toString().endsWith(".spec.ts")) {
                    continue;
                }
                for (String line : Files.readAllLines(path)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("test(") || trimmed.startsWith("test('") || trimmed.startsWith("test(\"")) {
                        tests++;
                    }
                }
            }
        }
        assertThat(tests).isBetween(1, 12);
        String vite = Files.readString(Path.of("frontend/vite.config.ts"));
        assertThat(vite).contains("'**/e2e/**'");
        String docs = Files.readString(Path.of("docs/e2e.md"));
        assertThat(docs).contains("测试自身不启停栈");
        assertThat(docs).contains("npm run e2e");
        assertThat(docs).doesNotContain("UI 质量有保障");
        assertThat(docs).doesNotContain("/Users/");
    }
}
