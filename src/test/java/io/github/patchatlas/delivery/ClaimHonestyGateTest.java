package io.github.patchatlas.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimHonestyGateTest {

    private static final Path README = Path.of("README.md");
    private static final Path REGISTER = Path.of("docs/claim-register.md");
    private static final Path ARCHITECTURE = Path.of("docs/architecture.md");
    private static final Path DIAGRAM = Path.of("docs/architecture-diagram.md");
    private static final Path DEMO = Path.of("docs/demo-script.md");

    @Test
    void publicMaterialsCarryDenominatorsAndNoRankingClaims() throws Exception {
        String readme = Files.readString(README);
        String register = Files.readString(REGISTER);
        String architecture = Files.readString(ARCHITECTURE);
        String diagram = Files.readString(DIAGRAM);
        String demo = Files.readString(DEMO);
        String fiveB = Files.readString(Path.of("benchmark-cases/batch5b-three-arm/reading-guide.md"));
        String spring = Files.readString(Path.of("benchmark-cases/spring-case-study/reading-guide.md"));
        String hostWorker = Files.readString(Path.of("docs/host-worker.md"));
        String hostWorkerVerification = Files.readString(Path.of("docs/host-worker-verification.md"));

        assertThat(readme).contains("1/6");
        assertThat(readme).contains("0/6");
        assertThat(readme).contains("4/18");
        assertThat(readme).contains("0/3");
        assertThat(readme).contains("0 / 0 / 0");
        assertThat(readme).contains("3/3");
        assertThat(readme).contains("不是** Agent");
        assertThat(readme).contains("18 次里的 1 次");
        assertThat(readme).contains("./scripts/up.sh");
        assertThat(demo).contains("18 次里的 1 次");
        assertThat(demo).contains("不执行会打印环境变量的命令");
        assertThat(demo).contains("6d7dd641-b730-45d7-890c-3fcdd3559f42");
        assertThat(fiveB).contains("1/6");
        assertThat(fiveB).contains("4/18");
        assertThat(spring).contains("0/3");
        assertThat(spring).contains("原因不再是队列性质");

        assertThat(architecture).doesNotContain("外部使用者无法自己产生一次 Run");
        assertThat(architecture).contains("./scripts/worker.sh");
        assertThat(hostWorker.indexOf("## 路径 A")).isGreaterThanOrEqualTo(0);
        assertThat(hostWorker.indexOf("## 路径 B")).isGreaterThan(hostWorker.indexOf("## 路径 A"));
        assertThat(hostWorkerVerification).contains("REPLAY OK");
        assertThat(hostWorkerVerification).contains("9a205591-2eb9-4a57-bc82-269e41a300e0");
        assertThat(hostWorkerVerification).contains("GENERATION_EXHAUSTED");
        assertThat(hostWorkerVerification).contains("不是 Agent 成绩");
        assertThat(hostWorkerVerification).doesNotContain("_待填_");

        for (String text : List.of(
                readme, register, architecture, diagram, demo, fiveB, spring, hostWorker, hostWorkerVerification)) {
            assertThat(text).doesNotContain("语义图引导");
            assertThat(text).doesNotContain("图更好");
            assertThat(text).doesNotContain("文本更好");
            assertThat(text).doesNotContain("综合分");
            assertThat(text).doesNotContain("优于");
            assertThat(text).doesNotContain("/Users/");
            assertThat(text).doesNotContain("Task 0");
        }

        assertThat(sha256("benchmark-cases/batch5b-three-arm/evidence-report.md"))
                .isEqualTo("fa10c9b3a7b78980ce0f7767b9e9ec111e47351c9b8a5effffc0a3747ebc2640");
        assertThat(sha256("benchmark-cases/batch5-three-arm/evidence-report.md"))
                .isEqualTo("8c78fa74e63f27b0a1e89f493ae10d36093d47a1804b8d2bb275990b3c7bbcbf");
        assertThat(sha256("benchmark-cases/spring-case-study/evidence-report.md"))
                .isEqualTo("638b60396b18ab5f35a832bbbae910154088fded9fb9c4d5db0aa2f0765eaba9");
        assertThat(sha256("benchmark-cases/task018/evidence-report.md"))
                .isEqualTo("d71e81f4bf2388d134222671fd47361db97ffd781ce7672e6a367e25389fbfa4");

        List<String> cells = new ArrayList<>();
        for (String line : register.split("\n")) {
            if (!line.startsWith("| A") && !line.startsWith("| B") && !line.startsWith("| C")) {
                continue;
            }
            String[] cols = line.split("\\|");
            assertThat(cols.length).isGreaterThanOrEqualTo(5);
            cells.add(cols[4].trim());
        }
        assertThat(cells).containsOnly("A", "B", "C");
        assertThat(cells.stream().filter("A"::equals).count()).isEqualTo(13);
        assertThat(cells.stream().filter("B"::equals).count()).isEqualTo(9);
        assertThat(cells.stream().filter("C"::equals).count()).isEqualTo(3);
    }

    private static String sha256(String path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path)));
        return HexFormat.of().formatHex(digest);
    }
}
