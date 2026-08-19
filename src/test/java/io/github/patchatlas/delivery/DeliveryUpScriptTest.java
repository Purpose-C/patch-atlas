package io.github.patchatlas.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DeliveryUpScriptTest {

    private static final Path ROOT = Path.of(".");
    private static final Path UP = Path.of("scripts/up.sh");
    private static final Path COMPOSE = Path.of("compose.yaml");

    @Test
    void packagedStackFilesExistAndDoNotReadDotEnv() throws IOException {
        String up = Files.readString(UP);
        String compose = Files.readString(COMPOSE);
        String dockerignore = Files.readString(Path.of(".dockerignore"));
        assertThat(Files.isRegularFile(Path.of("scripts/down.sh"))).isTrue();
        assertThat(Files.isRegularFile(Path.of("docker/app.Dockerfile"))).isTrue();
        assertThat(Files.isRegularFile(Path.of("docker/web.Dockerfile"))).isTrue();
        assertThat(Files.isRegularFile(Path.of("docker/nginx.conf"))).isTrue();
        assertThat(up).doesNotContain("source .env");
        assertThat(up).doesNotContain(". ./.env");
        assertThat(up).contains("--env-file");
        assertThat(up).contains("docker/compose.env");
        assertThat(up).contains("does not read .env");
        assertThat(compose).doesNotContain("env_file: .env");
        assertThat(compose).contains("dockerfile: docker/app.Dockerfile");
        assertThat(compose).contains("dockerfile: docker/web.Dockerfile");
        assertThat(compose).contains("PATCHATLAS_WORKER_ENABLED: \"false\"");
        assertThat(compose).contains("PATCHATLAS_GENERATOR_TYPE: FAKE");
        assertThat(compose).doesNotContain("/var/run/docker.sock");
        assertThat(dockerignore).contains(".env");
        assertThat(Files.readString(Path.of("docs/up.md"))).contains("./scripts/up.sh");
        String verification = Files.readString(Path.of("docs/up-verification.md"));
        assertThat(verification).contains("missing: OPENAI_API_KEY");
        assertThat(verification).contains("\"status\":\"UP\"");
        assertThat(verification).contains("\"items\":[]");
        assertThat(verification).contains("removing leftover container patchatlas-postgres-1");
        assertThat(verification).contains("连跑两次");
        assertThat(verification).doesNotContain("_待填_");
        assertThat(verification).doesNotContain("/Users/");
    }

    @Test
    void upScriptClearsLeftoverComposeNamesBeforeUp() throws IOException {
        String up = Files.readString(UP);
        assertThat(up).contains("patchatlas-postgres-1");
        assertThat(up).contains("patchatlas-app-1");
        assertThat(up).contains("patchatlas-web-1");
        assertThat(up).contains("docker rm -f");
        assertThat(up).contains("up --build -d --wait");
        assertThat(up).doesNotContain("down -v");
        assertThat(up).doesNotContain("volume rm");
    }

    @Test
    void workerFlagListsMissingCredentialsAndDoesNotStartCompose() throws Exception {
        ProcessResult result = runUp(Map.of(), "--worker");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("missing credentials or workspace");
        assertThat(result.stderr()).contains("missing: OPENAI_API_KEY");
        assertThat(result.stderr()).contains("missing: PATCHATLAS_OPENAI_MODEL");
        assertThat(result.stderr()).contains("missing: PATCHATLAS_WORKER_WORKSPACE_ROOT");
        assertThat(result.stderr()).contains("does not read .env");
        assertThat(result.stderr()).doesNotContain("compose up");
    }

    @Test
    void workerFlagStillRefusesWhenCredentialsArePresent() throws Exception {
        ProcessResult result = runUp(
                Map.of(
                        "OPENAI_API_KEY", "not-a-real-key",
                        "PATCHATLAS_OPENAI_MODEL", "dummy",
                        "PATCHATLAS_WORKER_WORKSPACE_ROOT",
                        ROOT.toAbsolutePath().toString()),
                "--worker");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("refusing to start Worker in this compose stack");
        assertThat(result.stderr()).contains("does not read .env");
        assertThat(result.stderr()).doesNotContain("missing: OPENAI_API_KEY");
    }

    private static ProcessResult runUp(Map<String, String> extraEnv, String... args)
            throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("bash");
        command.add(UP.toString());
        command.addAll(java.util.List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
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
