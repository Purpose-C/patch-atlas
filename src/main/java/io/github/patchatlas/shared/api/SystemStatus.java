package io.github.patchatlas.shared.api;

import java.time.Instant;

record SystemStatus(String name, String status, String version, RuntimeInfo runtime) {

    record RuntimeInfo(String javaVersion, Instant timestamp) {
    }
}
