package io.github.patchatlas.sandbox;

/** Docker 资源限制快照。 */
public record SandboxLimits(double cpus, long memoryBytes, int pidsLimit) {

    public SandboxLimits {
        if (!Double.isFinite(cpus) || cpus <= 0 || cpus > 8) {
            throw new IllegalArgumentException("cpus must be in (0, 8]");
        }
        if (memoryBytes < 128L * 1024 * 1024 || memoryBytes > 16L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("memoryBytes must be between 128 MiB and 16 GiB");
        }
        if (pidsLimit < 16 || pidsLimit > 4096) {
            throw new IllegalArgumentException("pidsLimit must be between 16 and 4096");
        }
    }

    public static SandboxLimits defaults() {
        return new SandboxLimits(1.0, 1_073_741_824L, 256);
    }
}
