package io.github.patchatlas.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DeliveryWorkerScriptTest {

    private static final Path ROOT = Path.of(".");
    private static final Path WORKER = Path.of("scripts/worker.sh");

    @Test
    void workerScriptDoesNotReadDotEnvOrMountDockerSockOrFallBackToFake() throws Exception {
        String worker = Files.readString(WORKER);
        assertThat(Files.isExecutable(WORKER) || Files.isRegularFile(WORKER)).isTrue();
        assertThat(worker).doesNotContain("source .env");
        assertThat(worker).doesNotContain(". ./.env");
        assertThat(worker).contains("does not read .env");
        assertThat(worker).doesNotContain("/var/run/docker.sock");
        assertThat(worker).contains("this script does not start a second database");
        assertThat(worker).contains("127.0.0.1:5432");
        assertThat(worker).contains("patchatlas.worker.enabled=true");
        assertThat(worker).contains("PATCHATLAS_GENERATOR_TYPE=OPENAI");
        assertThat(worker).contains("does not silently switch to FAKE");
        assertThat(worker).contains("./scripts/up.sh");
        assertThat(worker).doesNotContain("OPENAI_API_KEY=");
    }

    @Test
    void missingCredentialsPrintMissingListAndExitTwo() throws Exception {
        ProcessResult result = runWorker(Map.of());
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("missing credentials or workspace");
        assertThat(result.stderr()).contains("missing: OPENAI_API_KEY");
        assertThat(result.stderr()).contains("missing: PATCHATLAS_OPENAI_MODEL");
        assertThat(result.stderr()).contains("missing: PATCHATLAS_WORKER_WORKSPACE_ROOT");
        assertThat(result.stderr()).contains("does not read .env");
        assertThat(result.stderr()).doesNotContain("starting host Worker");
        assertThat(result.stdout() + result.stderr()).doesNotContain("sk-");
    }

    @Test
    void fakeGeneratorTypeIsRefusedEvenWhenCredentialsArePresent() throws Exception {
        ProcessResult result = runWorker(
                Map.of(
                        "OPENAI_API_KEY", "not-a-real-key",
                        "PATCHATLAS_OPENAI_MODEL", "dummy",
                        "PATCHATLAS_WORKER_WORKSPACE_ROOT",
                        ROOT.toAbsolutePath().toString(),
                        "PATCHATLAS_GENERATOR_TYPE",
                        "FAKE"));
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("does not start a FAKE generator");
        assertThat(result.stderr()).contains("does not read .env");
        assertThat(result.stderr()).doesNotContain("not-a-real-key");
        assertThat(result.stderr()).doesNotContain("starting host Worker");
    }

    private static ProcessResult runWorker(Map<String, String> extraEnv) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", WORKER.toString());
        builder.directory(ROOT.toFile());
        builder.redirectErrorStream(false);
        Map<String, String> env = builder.environment();
        env.remove("OPENAI_API_KEY");
        env.remove("PATCHATLAS_OPENAI_MODEL");
        env.remove("PATCHATLAS_WORKER_WORKSPACE_ROOT");
        env.remove("PATCHATLAS_WORKER_ENABLED");
        env.remove("PATCHATLAS_GENERATOR_TYPE");
        env.putAll(extraEnv);
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
