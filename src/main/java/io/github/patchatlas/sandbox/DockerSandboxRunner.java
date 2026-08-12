package io.github.patchatlas.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 使用 Docker CLI 执行白名单 Maven 命令。 */
public final class DockerSandboxRunner implements SandboxRunner {

    private static final Duration PREFLIGHT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);

    private final DockerSandboxConfig config;
    private final CommandExecutor commandExecutor;
    private final HostIdentityProvider hostIdentityProvider;
    private final Supplier<String> containerNameSupplier;

    public DockerSandboxRunner(DockerSandboxConfig config) {
        this(
                config,
                new ProcessBuilderCommandExecutor(),
                DockerSandboxRunner::readUnixIdentity,
                () -> "patch-atlas-" + UUID.randomUUID());
    }

    DockerSandboxRunner(
            DockerSandboxConfig config,
            CommandExecutor commandExecutor,
            HostIdentityProvider hostIdentityProvider,
            Supplier<String> containerNameSupplier) {
        this.config = Objects.requireNonNull(config, "config");
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
        this.hostIdentityProvider = Objects.requireNonNull(hostIdentityProvider, "hostIdentityProvider");
        this.containerNameSupplier = Objects.requireNonNull(containerNameSupplier, "containerNameSupplier");
    }

    @Override
    public SandboxExecution execute(Path workspace, MavenSandboxCommand command) {
        Objects.requireNonNull(command, "command");
        Path normalizedWorkspace = normalizeWorkspace(workspace);
        if (normalizedWorkspace == null) {
            return localFailure(
                    SandboxExecutionStatus.WORKSPACE_UNAVAILABLE,
                    "workspace must be an existing writable directory",
                    command);
        }

        Path cacheDirectory = prepareCacheDirectory();
        if (cacheDirectory == null) {
            return localFailure(
                    SandboxExecutionStatus.CACHE_UNAVAILABLE,
                    "Maven cache directory is not writable",
                    command);
        }

        String userSpec;
        try {
            userSpec = hostIdentityProvider.userSpec(normalizedWorkspace);
        } catch (IOException | RuntimeException ex) {
            return localFailure(
                    SandboxExecutionStatus.WORKSPACE_UNAVAILABLE,
                    "cannot determine non-root workspace owner",
                    command);
        }

        CommandExecution dockerInfo = commandExecutor.execute(
                List.of("docker", "info", "--format", "{{.ServerVersion}}"),
                PREFLIGHT_TIMEOUT,
                config.maxOutputBytes());
        if (dockerInfo.startFailure() != null || dockerInfo.timedOut() || !isSuccessful(dockerInfo)) {
            return fromProcess(
                    SandboxExecutionStatus.DOCKER_UNAVAILABLE,
                    dockerInfo,
                    List.of(),
                    command);
        }

        SandboxExecution imageFailure = ensureImage(command);
        if (imageFailure != null) {
            return imageFailure;
        }

        String containerName = containerNameSupplier.get();
        List<String> createCommand = buildDockerCreateCommand(
                normalizedWorkspace, cacheDirectory, userSpec, containerName, command);
        CommandExecution creation = commandExecutor.execute(
                createCommand, PREFLIGHT_TIMEOUT, config.maxOutputBytes());
        if (!isSuccessful(creation)) {
            commandExecutor.execute(
                    List.of("docker", "rm", "-f", containerName),
                    CLEANUP_TIMEOUT,
                    config.maxOutputBytes());
            return fromProcess(
                    SandboxExecutionStatus.CONTAINER_SETUP_FAILED,
                    creation,
                    command.arguments(),
                    command);
        }

        CommandExecution process = commandExecutor.execute(
                List.of("docker", "start", "-a", containerName),
                config.timeout(),
                config.maxOutputBytes());
        CommandExecution cleanup = commandExecutor.execute(
                List.of("docker", "rm", "-f", containerName),
                CLEANUP_TIMEOUT,
                config.maxOutputBytes());
        if (process.timedOut()) {
            SandboxExecutionStatus status = isSuccessful(cleanup)
                    ? SandboxExecutionStatus.TIMED_OUT
                    : SandboxExecutionStatus.TIMEOUT_CLEANUP_FAILED;
            return fromProcess(status, process, command.arguments(), command);
        }
        if (process.startFailure() != null) {
            return fromProcess(
                    SandboxExecutionStatus.PROCESS_START_FAILED,
                    process,
                    command.arguments(),
                    command);
        }
        if (!isSuccessful(cleanup)) {
            return fromProcess(
                    SandboxExecutionStatus.CLEANUP_FAILED,
                    process,
                    command.arguments(),
                    command);
        }
        return fromProcess(
                SandboxExecutionStatus.COMPLETED, process, command.arguments(), command);
    }

    private SandboxExecution ensureImage(MavenSandboxCommand command) {
        List<String> inspectCommand = List.of("docker", "image", "inspect", command.image());
        CommandExecution inspect = commandExecutor.execute(
                inspectCommand, PREFLIGHT_TIMEOUT, config.maxOutputBytes());
        if (isSuccessful(inspect)) {
            return null;
        }

        List<String> pullCommand = List.of("docker", "pull", command.image());
        CommandExecution pull = commandExecutor.execute(
                pullCommand, config.timeout(), config.maxOutputBytes());
        if (isSuccessful(pull)) {
            return null;
        }
        return fromProcess(
                SandboxExecutionStatus.IMAGE_UNAVAILABLE, pull, List.of(), command);
    }

    private List<String> buildDockerCreateCommand(
            Path workspace,
            Path cacheDirectory,
            String userSpec,
            String containerName,
            MavenSandboxCommand command) {
        SandboxLimits limits = config.limits();
        List<String> arguments = new ArrayList<>(List.of(
                "docker",
                "create",
                "--name",
                containerName,
                "--pull=never",
                "--user",
                userSpec,
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--cpus=" + limits.cpus(),
                "--memory=" + limits.memoryBytes(),
                "--pids-limit=" + limits.pidsLimit(),
                "-e",
                "HOME=/tmp",
                "-e",
                "MAVEN_CONFIG=/maven-cache/config",
                command.networkMode() == MavenNetworkMode.OFFLINE
                        ? "--network=none"
                        : "--network=bridge",
                "-v",
                workspace + ":/workspace:rw",
                "-v",
                cacheDirectory + ":/maven-cache:rw",
                "-w",
                "/workspace",
                command.image()));
        arguments.addAll(command.arguments());
        return List.copyOf(arguments);
    }

    private Path normalizeWorkspace(Path workspace) {
        if (workspace == null) {
            return null;
        }
        try {
            Path realPath = workspace.toRealPath();
            return realPath.startsWith(config.workspaceRoot())
                            && Files.isDirectory(realPath)
                            && Files.isWritable(realPath)
                            && isDockerBindable(realPath)
                    ? realPath
                    : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private Path prepareCacheDirectory() {
        try {
            Path cache = config.mavenCacheDirectory().toAbsolutePath().normalize();
            Files.createDirectories(cache);
            Path realPath = cache.toRealPath();
            return hasDedicatedCacheSegment(realPath)
                            && Files.isDirectory(realPath)
                            && Files.isWritable(realPath)
                            && isDockerBindable(realPath)
                    ? realPath
                    : null;
        } catch (IOException | SecurityException ex) {
            return null;
        }
    }

    private boolean isDockerBindable(Path path) {
        String value = path.toString();
        return !value.contains(":") && !value.contains("\n") && !value.contains("\r");
    }

    private boolean hasDedicatedCacheSegment(Path path) {
        for (Path segment : path) {
            if (segment.toString().equals(".patch-atlas-cache")) {
                return true;
            }
        }
        return false;
    }

    private SandboxExecution localFailure(
            SandboxExecutionStatus status, String message, MavenSandboxCommand command) {
        return new SandboxExecution(
                status,
                null,
                Duration.ZERO,
                false,
                List.of(),
                message,
                command.image(),
                config.limits(),
                command.networkMode());
    }

    private SandboxExecution fromProcess(
            SandboxExecutionStatus status,
            CommandExecution process,
            List<String> actualCommand,
            MavenSandboxCommand command) {
        String log = process.startFailure() == null ? process.output() : process.startFailure();
        return new SandboxExecution(
                status,
                process.exitCode(),
                process.elapsed(),
                process.timedOut(),
                actualCommand,
                log,
                command.image(),
                config.limits(),
                command.networkMode());
    }

    private boolean isSuccessful(CommandExecution execution) {
        return execution.startFailure() == null
                && !execution.timedOut()
                && execution.exitCode() != null
                && execution.exitCode() == 0;
    }

    private static String readUnixIdentity(Path workspace) throws IOException {
        Map<String, Object> attributes = Files.readAttributes(workspace, "unix:uid,gid");
        return attributes.get("uid") + ":" + attributes.get("gid");
    }
}
