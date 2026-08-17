package io.github.patchatlas.run;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LocatingProperties.class)
public class LocatingConfiguration {}
