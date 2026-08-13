package io.github.patchatlas.benchmark;

import io.github.patchatlas.repository.RepositoryUrls;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Exact-revision Git workspaces for benchmark preparation; never executes repository hooks. */
public final class BenchmarkGitWorkspace {

    public static final Duration CLONE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration CHECKOUT_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    enum FailureCode {
        CLONE_FAILED,
        CHECKOUT_FAILED
    }

    public sealed interface CheckoutResult
            permits CheckoutResult.Success, CheckoutResult.Failure {
        record Success(Path workspace) implements CheckoutResult {
            public Success {
                Objects.requireNonNull(workspace, "workspace");
            }
        }

        record Failure(FailureCode code, String summary) implements CheckoutResult {
            public Failure {
                Objects.requireNonNull(code, "code");
                Objects.requireNonNull(summary, "summary");
            }
        }
    }

    @FunctionalInterface
    interface CommandRunner {
        CommandResult execute(List<String> command, Duration timeout);
    }

    record CommandResult(Integer exitCode, boolean timedOut, String output) {}

    private final Path cacheRoot;
    private final CommandRunner runner;

    public BenchmarkGitWorkspace(Path cacheRoot) {
        this(cacheRoot, BenchmarkGitWorkspace::run);
    }

    BenchmarkGitWorkspace(Path cacheRoot, CommandRunner runner) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public CheckoutResult checkout(String repositoryUrl, String revision, String caseId) {
        RepositoryUrls.requireAnonymousGithubHttps(repositoryUrl);
        if (revision == null || !revision.matches("^[0-9a-f]{40}$")) {
            throw new IllegalArgumentException("revision must be 40 lowercase hex chars");
        }
        if (caseId == null || !caseId.matches("^[A-Za-z0-9_.-]{1,128}$")) {
            throw new IllegalArgumentException("caseId must be one safe path segment");
        }

        try {
            Files.createDirectories(cacheRoot.resolve("repos"));
            Files.createDirectories(cacheRoot.resolve("workspaces"));
        } catch (IOException e) {
            return new CheckoutResult.Failure(FailureCode.CLONE_FAILED, "cannot create cache directories");
        }

        Path repositoryCache = cacheRoot.resolve("repos").resolve(repositorySlug(repositoryUrl) + ".git");
        if (!Files.exists(repositoryCache)) {
            CommandResult clone = runner.execute(
                    List.of(
                            "git",
                            "clone",
                            "--bare",
                            "--filter=blob:none",
                            repositoryUrl,
                            repositoryCache.toString()),
                    CLONE_TIMEOUT);
            if (!successful(clone) || !Files.isDirectory(repositoryCache)) {
                return new CheckoutResult.Failure(FailureCode.CLONE_FAILED, summarize(clone));
            }
        } else if (!Files.isDirectory(repositoryCache)) {
            return new CheckoutResult.Failure(FailureCode.CLONE_FAILED, "repository cache is not a directory");
        }

        Path workspace = cacheRoot.resolve("workspaces")
                .resolve(caseId + "-" + revision.substring(0, 12));
        if (Files.exists(workspace)) {
            CommandResult head = runner.execute(
                    List.of("git", "-C", workspace.toString(), "rev-parse", "HEAD"),
                    Duration.ofSeconds(30));
            if (successful(head) && revision.equals(head.output().strip())) {
                return new CheckoutResult.Success(workspace);
            }
            return new CheckoutResult.Failure(
                    FailureCode.CHECKOUT_FAILED, "existing workspace revision mismatch");
        }

        CommandResult worktree = runner.execute(
                List.of(
                        "git",
                        "--git-dir",
                        repositoryCache.toString(),
                        "worktree",
                        "add",
                        "--detach",
                        workspace.toString(),
                        revision),
                CHECKOUT_TIMEOUT);
        if (!successful(worktree) || !Files.isDirectory(workspace)) {
            return new CheckoutResult.Failure(FailureCode.CHECKOUT_FAILED, summarize(worktree));
        }
        return new CheckoutResult.Success(workspace);
    }

    private static String repositorySlug(String repositoryUrl) {
        String[] segments = URI.create(repositoryUrl).getPath().substring(1).split("/");
        String repository = segments[1].endsWith(".git")
                ? segments[1].substring(0, segments[1].length() - 4)
                : segments[1];
        return segments[0] + "-" + repository;
    }

    private static boolean successful(CommandResult result) {
        return result != null
                && !result.timedOut()
                && result.exitCode() != null
                && result.exitCode() == 0;
    }

    private static String summarize(CommandResult result) {
        if (result == null) {
            return "git command did not return a result";
        }
        if (result.timedOut()) {
            return "git command timed out";
        }
        if (result.exitCode() == null) {
            return "git command could not start";
        }
        return "git command failed with exit " + result.exitCode();
    }

    private static CommandResult run(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new CommandResult(null, false, "");
        }

        BoundedOutput output = new BoundedOutput(MAX_OUTPUT_BYTES);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> reader = executor.submit(() -> drain(process.getInputStream(), output));
            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            if (!finished) {
                process.destroyForcibly();
            }
            try {
                reader.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                reader.cancel(true);
            }
            return new CommandResult(
                    finished ? process.exitValue() : null, !finished, output.text());
        }
    }

    private static void drain(InputStream input, BoundedOutput output) {
        byte[] buffer = new byte[8192];
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.append(buffer, read);
            }
        } catch (IOException ignored) {
            // A stable failure is returned by the process exit/timeout path.
        }
    }

    private static final class BoundedOutput {
        private final byte[] bytes;
        private int size;

        private BoundedOutput(int capacity) {
            bytes = new byte[capacity];
        }

        private void append(byte[] source, int length) {
            if (length >= bytes.length) {
                System.arraycopy(source, length - bytes.length, bytes, 0, bytes.length);
                size = bytes.length;
                return;
            }
            int overflow = Math.max(0, size + length - bytes.length);
            if (overflow > 0) {
                System.arraycopy(bytes, overflow, bytes, 0, size - overflow);
                size -= overflow;
            }
            System.arraycopy(source, 0, bytes, size, length);
            size += length;
        }

        private String text() {
            return new String(bytes, 0, size, StandardCharsets.UTF_8);
        }
    }
}
