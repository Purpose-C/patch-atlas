package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Historical / Live 请求在类型上分离：Live 调用链拿不到 Fixed 工作区字段。
 */
class ReplayRequestTypeBoundaryTest {

    @TempDir
    Path tempDir;

    @Test
    void liveRequestHasNoFixedWorkspaceAccessor() {
        Set<String> methods = Arrays.stream(LiveReplayRequest.class.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class)
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(methods)
                .doesNotContain("fixedWorkspace", "fixedRevision", "getFixedWorkspace", "oracleData");
        assertThat(methods).contains("workspace", "command", "targetTest");
    }

    @Test
    void historicalRequestExposesBothWorkspaces() throws Exception {
        Path buggy = tempDir.resolve("buggy");
        Path fixed = tempDir.resolve("fixed");
        Files.createDirectories(buggy);
        Files.createDirectories(fixed);
        MavenTestCommand command = new MavenTestCommand("", "com.example.ATest", MavenNetworkMode.OFFLINE);
        TargetTest target = new TargetTest("com.example.ATest", "detectsBug");

        HistoricalReplayRequest request = new HistoricalReplayRequest(buggy, fixed, command, target);

        assertThat(request.buggyWorkspace()).isEqualTo(buggy);
        assertThat(request.fixedWorkspace()).isEqualTo(fixed);
        assertThat(request.command()).isEqualTo(command);
        assertThat(request.targetTest()).isEqualTo(target);
    }

    @Test
    void liveRequestOnlyExposesDefectWorkspace() {
        Path workspace = tempDir.resolve("current");
        MavenTestCommand command = new MavenTestCommand("", "com.example.ATest", MavenNetworkMode.ONLINE);
        TargetTest target = new TargetTest("com.example.ATest", "detectsBug");

        LiveReplayRequest request = new LiveReplayRequest(workspace, command, target);

        assertThat(request.workspace()).isEqualTo(workspace);
        assertThat(request.command()).isEqualTo(command);
        assertThat(request.targetTest()).isEqualTo(target);
    }

    @Test
    void targetTestRejectsBlankNames() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TargetTest(" ", "method"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TargetTest("com.example.A", ""));
        assertThatNullPointerException().isThrownBy(() -> new TargetTest(null, "m"));
    }

    @Test
    void historicalRequestRejectsNullParts() throws Exception {
        Path buggy = tempDir.resolve("buggy");
        Path fixed = tempDir.resolve("fixed");
        Files.createDirectories(buggy);
        Files.createDirectories(fixed);
        MavenTestCommand command = new MavenTestCommand("", "com.example.ATest", MavenNetworkMode.OFFLINE);
        TargetTest target = new TargetTest("com.example.ATest", "m");

        assertThatNullPointerException()
                .isThrownBy(() -> new HistoricalReplayRequest(null, fixed, command, target));
        assertThatNullPointerException()
                .isThrownBy(() -> new HistoricalReplayRequest(buggy, null, command, target));
        assertThatNullPointerException()
                .isThrownBy(() -> new HistoricalReplayRequest(buggy, fixed, null, target));
        assertThatNullPointerException()
                .isThrownBy(() -> new HistoricalReplayRequest(buggy, fixed, command, null));
    }
}
