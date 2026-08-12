package io.github.patchatlas.run;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code patchatlas.worker.*} 配置。 */
@ConfigurationProperties(prefix = "patchatlas.worker")
public class RunWorkerProperties {

    /** 为 true 时装配 Worker 并在启动时 drain 未完成 Run。 */
    private boolean enabled = false;

    private String owner = "patchatlas-worker";

    private Duration leaseDuration = Issue2TestWorker.DEFAULT_LEASE;

    private Duration heartbeatInterval = Issue2TestWorker.DEFAULT_HEARTBEAT;

    /** Patch Gate 可信 workspace 根；必须已存在。 */
    private Path workspaceRoot;

    /** 启动时最多连续处理的 Run 数（防止无限循环）。 */
    private int startupMaxRuns = 256;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public int getStartupMaxRuns() {
        return startupMaxRuns;
    }

    public void setStartupMaxRuns(int startupMaxRuns) {
        this.startupMaxRuns = startupMaxRuns;
    }
}
