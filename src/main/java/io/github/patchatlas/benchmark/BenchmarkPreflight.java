package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Shared batch preflight. Checks only cross-case prerequisites; case-specific
 * checkout, warmup, and generation belong to the formal run and enter the denominator.
 */
public final class BenchmarkPreflight {

    public static final long MIN_FREE_BYTES = 20L * 1024 * 1024 * 1024;
    private static final Duration DOCKER_TIMEOUT = Duration.ofSeconds(15);

    public sealed interface Result permits Result.Ready, Result.NotReady {
        record Ready() implements Result {}

        record NotReady(List<String> reasons) implements Result {
            public NotReady {
                reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
                if (reasons.isEmpty()) {
                    throw new IllegalArgumentException("NotReady requires at least one reason");
                }
            }
        }
    }

    public interface PostgresProbe {
        void ping();
    }

    public interface DockerProbe {
        boolean daemonReady();

        boolean imagePresent(String image);
    }

    private final PostgresProbe postgres;
    private final DockerProbe docker;
    private final LongSupplier freeBytes;
    private final Supplier<String> apiKey;

    public BenchmarkPreflight(DataSource dataSource, Path workspaceRoot) {
        this(
                () -> pingPostgres(dataSource),
                new ProcessDockerProbe(),
                () -> usableBytes(workspaceRoot),
                () -> System.getenv("OPENAI_API_KEY"));
    }

    BenchmarkPreflight(
            PostgresProbe postgres,
            DockerProbe docker,
            LongSupplier freeBytes,
            Supplier<String> apiKey) {
        this.postgres = Objects.requireNonNull(postgres, "postgres");
        this.docker = Objects.requireNonNull(docker, "docker");
        this.freeBytes = Objects.requireNonNull(freeBytes, "freeBytes");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
    }

    public Result check(Cohort cohort) {
        Objects.requireNonNull(cohort, "cohort");
        List<String> reasons = new ArrayList<>();

        String recomputed = BenchmarkArtifacts.cohortSha256(cohort.cases());
        if (!recomputed.equals(cohort.cohortSha256())) {
            reasons.add("cohort digest mismatch");
        }

        try {
            postgres.ping();
        } catch (RuntimeException ex) {
            reasons.add("postgresql unavailable");
        }

        if (!docker.daemonReady()) {
            reasons.add("docker unavailable");
        } else {
            for (String image : requiredImages(cohort)) {
                if (!docker.imagePresent(image)) {
                    reasons.add("required maven image missing");
                    break;
                }
            }
        }

        try {
            if (freeBytes.getAsLong() < MIN_FREE_BYTES) {
                reasons.add("insufficient disk space");
            }
        } catch (RuntimeException ex) {
            reasons.add("disk space unavailable");
        }

        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            reasons.add("openai api key missing");
        }

        if (reasons.isEmpty()) {
            return new Result.Ready();
        }
        return new Result.NotReady(reasons);
    }

    private static Set<String> requiredImages(Cohort cohort) {
        Set<String> images = new LinkedHashSet<>();
        for (var item : cohort.cases()) {
            images.add(new MavenExecutionPolicy(item.javaVersion(), MavenNetworkMode.OFFLINE).image());
        }
        return images;
    }

    private static void pingPostgres(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1")) {
            if (!rs.next()) {
                throw new IllegalStateException("postgresql ping returned no row");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("postgresql ping failed");
        }
    }

    private static long usableBytes(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        try {
            Path existing = workspaceRoot;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                throw new IllegalStateException("no filesystem root");
            }
            FileStore store = Files.getFileStore(existing);
            return store.getUsableSpace();
        } catch (IOException ex) {
            throw new IllegalStateException("disk probe failed");
        }
    }

    static final class ProcessDockerProbe implements DockerProbe {
        @Override
        public boolean daemonReady() {
            return exitZero(List.of("docker", "info"));
        }

        @Override
        public boolean imagePresent(String image) {
            return exitZero(List.of("docker", "image", "inspect", image));
        }

        private static boolean exitZero(List<String> command) {
            try {
                Process process = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                boolean finished = process.waitFor(DOCKER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }
    }
}
