package io.github.patchatlas.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ArchitectureDiagramMappingTest {

    private static final Path DOC = Path.of("docs/architecture-diagram.md");
    private static final Pattern NODE = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9]*)\\[\"");
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\| ([a-zA-Z][a-zA-Z0-9]*) \\| `([^`]+)`");

    @Test
    void everyDiagramNodeMapsToAnExistingType() throws IOException {
        String doc = Files.readString(DOC);
        assertThat(doc).doesNotContain("语义图引导");
        assertThat(doc).doesNotContain("图更好");
        assertThat(doc).contains("expand");
        assertThat(doc).contains("三次观测均为 0 次");

        Set<String> nodes = new LinkedHashSet<>();
        Matcher nodeMatcher = NODE.matcher(doc);
        while (nodeMatcher.find()) {
            nodes.add(nodeMatcher.group(1));
        }
        assertThat(nodes.size()).isGreaterThanOrEqualTo(20);

        List<String> tableIds = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        Matcher rowMatcher = TABLE_ROW.matcher(doc);
        while (rowMatcher.find()) {
            tableIds.add(rowMatcher.group(1));
            targets.add(rowMatcher.group(2));
        }
        assertThat(tableIds).containsExactlyInAnyOrderElementsOf(nodes);

        for (String target : targets) {
            for (String part : target.split(", ")) {
                assertExisting(part.trim());
            }
        }
    }

    private static void assertExisting(String target) throws IOException {
        if (target.startsWith("frontend/")) {
            assertThat(Path.of(target)).exists();
            return;
        }
        assertThat(target).startsWith("io.github.patchatlas.");
        Path dir = Path.of("src/main/java/io/github/patchatlas");
        String[] parts = target.substring("io.github.patchatlas.".length()).split("\\.");
        Path javaFile = null;
        for (int i = 0; i < parts.length; i++) {
            Path candidate = dir.resolve(parts[i] + ".java");
            if (Files.isRegularFile(candidate)) {
                javaFile = candidate;
                break;
            }
            dir = dir.resolve(parts[i]);
        }
        assertThat(javaFile).as("mapped type %s", target).isNotNull();
        assertThat(javaFile).exists();
        assertThat(Files.readString(javaFile)).contains(parts[parts.length - 1]);
    }
}
