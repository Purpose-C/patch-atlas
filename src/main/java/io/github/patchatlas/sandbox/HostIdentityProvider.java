package io.github.patchatlas.sandbox;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface HostIdentityProvider {

    String userSpec(Path workspace) throws IOException;
}
