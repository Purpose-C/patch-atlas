package io.github.patchatlas.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 固定的 Maven 单测命令模板。空 modulePath 表示仓库根模块。 */
public record MavenTestCommand(
        String modulePath, String testSelector, MavenNetworkMode networkMode)
        implements MavenSandboxCommand {

    public MavenTestCommand {
        MavenCommandValidation.requireSafeModulePath(modulePath);
        MavenCommandValidation.requireSafeTestSelector(testSelector);
        Objects.requireNonNull(networkMode, "networkMode");
    }

    @Override
    public List<String> arguments() {
        List<String> arguments = new ArrayList<>(List.of(
                "mvn", "-B", "-Dmaven.repo.local=/maven-cache/repository"));
        if (networkMode == MavenNetworkMode.OFFLINE) {
            arguments.add("-o");
        }
        if (!modulePath.isEmpty()) {
            arguments.add("-pl");
            arguments.add(modulePath);
            arguments.add("-am");
        }
        arguments.add("-Dtest=" + testSelector);
        arguments.add("test");
        return List.copyOf(arguments);
    }
}
