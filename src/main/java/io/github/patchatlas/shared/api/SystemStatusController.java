package io.github.patchatlas.shared.api;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SystemStatusController {

    private final Clock clock;
    private final BuildProperties buildProperties;

    @Autowired
    SystemStatusController(BuildProperties buildProperties) {
        this(Clock.systemUTC(), buildProperties);
    }

    SystemStatusController(Clock clock, BuildProperties buildProperties) {
        this.clock = clock;
        this.buildProperties = buildProperties;
    }

    @GetMapping("/health")
    SystemStatus health() {
        return new SystemStatus(
                "PatchAtlas",
                "UP",
                buildProperties.getVersion(),
                new SystemStatus.RuntimeInfo(System.getProperty("java.version"), clock.instant())
        );
    }
}
