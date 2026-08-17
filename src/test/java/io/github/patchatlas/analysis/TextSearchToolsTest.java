package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextSearchToolsTest {

    @TempDir
    Path temp;

    @Test
    void rejectsTraversalAbsoluteAndSymlinkEscapeWithoutLeakingOutsideContent() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        Path secret = temp.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET");
        Files.writeString(workspace.resolve("inside.txt"), "visible");
        Files.createSymbolicLink(workspace.resolve("escape"), secret);

        TextSearchTools tools = new TextSearchTools(workspace);

        assertRejected(tools, "../secret.txt");
        assertRejected(tools, "/etc/passwd");
        assertThatThrownBy(() -> tools.read("escape", 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path rejected")
                .hasMessageNotContaining("TOP-SECRET")
                .hasMessageNotContaining(secret.toString());
        assertThatThrownBy(() -> tools.list(".."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path rejected")
                .hasMessageNotContaining(temp.toString());
    }

    @Test
    void publicTypesDoNotAcceptFixedRevisionOrOracle() {
        Set<String> offenders = new HashSet<>();
        for (Class<?> type : List.of(LocalizationTools.class, TextSearchTools.class)) {
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                collect(method.getReturnType(), offenders);
                for (Class<?> param : method.getParameterTypes()) {
                    collect(param, offenders);
                }
            }
        }
        assertThat(offenders).isEmpty();
        assertThat(TextSearchTools.class.getConstructors()[0].getParameterCount()).isEqualTo(1);
        assertThat(TextSearchTools.class.getConstructors()[0].getParameterTypes()[0]).isEqualTo(Path.class);
    }

    @Test
    void truncatesSearchListAndReadToHardCaps() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("cap"));
        StringBuilder huge = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            huge.append("hit line ").append(i).append('\n');
        }
        Files.writeString(workspace.resolve("big.txt"), huge);
        for (int i = 0; i < LocalizationTools.MAX_LIST_ENTRIES + 5; i++) {
            Files.writeString(workspace.resolve("f" + i + ".txt"), "hit " + i);
        }

        TextSearchTools tools = new TextSearchTools(workspace);
        LocalizationTools.SearchHits hits = tools.search("hit", "*.txt");
        assertThat(hits.hits()).hasSize(LocalizationTools.MAX_SEARCH_HITS);
        assertThat(hits.truncated()).isTrue();

        LocalizationTools.DirectoryListing listing = tools.list(".");
        assertThat(listing.names()).hasSize(LocalizationTools.MAX_LIST_ENTRIES);
        assertThat(listing.truncated()).isTrue();

        LocalizationTools.FileSlice slice = tools.read("big.txt", 1, 1000);
        assertThat(slice.lines()).hasSize(LocalizationTools.MAX_READ_LINES);
        assertThat(slice.truncated()).isTrue();
    }

    private static void assertRejected(TextSearchTools tools, String path) {
        assertThatThrownBy(() -> tools.read(path, 1, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path rejected");
        assertThatThrownBy(() -> tools.list(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path rejected");
    }

    private static void collect(Class<?> type, Set<String> offenders) {
        String name = type.getName();
        if (name.contains("Oracle")
                || name.contains("Fixed")
                || name.startsWith("io.github.patchatlas.benchmark.")) {
            offenders.add(name);
        }
    }

}
