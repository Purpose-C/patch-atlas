package io.github.patchatlas.sandbox;

import java.util.ArrayList;
import java.util.List;

/** 通过联网执行精确目标测试预热实际所需依赖；测试断言失败不阻断预热。 */
public record MavenDependencyWarmupCommand(
        String modulePath, String testSelector, String javaVersion)
        implements MavenSandboxCommand {

    public MavenDependencyWarmupCommand {
        MavenCommandValidation.requireSafeModulePath(modulePath);
        MavenCommandValidation.requireSafeTestSelector(testSelector);
        new MavenExecutionPolicy(javaVersion, MavenNetworkMode.ONLINE);
    }

    public MavenDependencyWarmupCommand(String modulePath, String testSelector) {
        this(modulePath, testSelector, MavenExecutionPolicy.DEFAULT_JAVA_VERSION);
    }

    @Override
    public List<String> arguments() {
        List<String> arguments = new ArrayList<>(List.of(
                "mvn", "-B", "-Dmaven.repo.local=/maven-cache/repository"));
        if (!modulePath.isEmpty()) {
            arguments.add("-pl");
            arguments.add(modulePath);
            arguments.add("-am");
        }
        arguments.add("-Dtest=" + testSelector);
        arguments.add("-Dsurefire.failIfNoSpecifiedTests=false");
        arguments.add("-Dmaven.test.failure.ignore=true");
        arguments.add("test");
        return List.copyOf(arguments);
    }

    @Override
    public MavenNetworkMode networkMode() {
        return MavenNetworkMode.ONLINE;
    }
}
