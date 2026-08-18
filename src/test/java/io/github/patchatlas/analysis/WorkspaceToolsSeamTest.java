package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 工作区读写只有一份实现；文本发现不复制 read/submit。 */
class WorkspaceToolsSeamTest {

    private static final Path ANALYSIS_DIR =
            Path.of("src/main/java/io/github/patchatlas/analysis");

    @TempDir
    Path temp;

    @Test
    void onlyWorkspaceFileToolsImplementsWorkspaceToolsInProduction() throws Exception {
        List<String> implementors = new ArrayList<>();
        for (Class<?> type : loadProductionTypes()) {
            if (type.isInterface() || type.isEnum() || type.isRecord()) {
                continue;
            }
            if (WorkspaceTools.class.isAssignableFrom(type)) {
                implementors.add(type.getName());
            }
        }
        assertThat(implementors).containsExactly(WorkspaceFileTools.class.getName());
    }

    @Test
    void textSearchToolsDelegatesReadAndSubmitToTheSharedWorkspaceToolsInstance() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        TextSearchTools tools = new TextSearchTools(workspace);
        WorkspaceTools shared = tools.workspaceTools();

        assertThat(shared).isInstanceOf(WorkspaceFileTools.class);
        assertThat(tools.read("a.txt", 1, 10)).isEqualTo(shared.read("a.txt", 1, 10));
        assertThat(tools.validateSubmit(List.of("a.txt")))
                .isEqualTo(shared.validateSubmit(List.of("a.txt")));
    }

    @Test
    void textDiscoveryToolsIsTheSearchAndListSeamAndDoesNotImplementWorkspaceTools() {
        assertThat(DiscoveryTools.class).isAssignableFrom(TextDiscoveryTools.class);
        assertThat(WorkspaceTools.class.isAssignableFrom(TextDiscoveryTools.class)).isFalse();
        assertThat(TextSearchTools.class.getConstructors()[0].getParameterCount()).isEqualTo(1);
        assertThat(TextSearchTools.class.getConstructors()[0].getParameterTypes()[0]).isEqualTo(Path.class);
    }

    @Test
    void textDiscoverySearchMatchesTextSearchToolsOnTheSameWorkspace() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("scan"));
        Files.writeString(workspace.resolve("hit.txt"), "needle here\n");
        TextSearchTools tools = new TextSearchTools(workspace);
        TextDiscoveryTools discovery = tools.discoveryTools();

        LocalizationTools.SearchHits viaFacade = tools.search("needle", "*.txt");
        LocalizationTools.SearchHits viaDiscovery = discovery.search("needle", "*.txt");
        assertThat(viaDiscovery).isEqualTo(viaFacade);
        assertThat(tools.list(".")).isEqualTo(discovery.list("."));
    }

    @Test
    void textDiscoveryInvokeSerializesSearchAndListAndRejectsUnknownTools() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("invoke"));
        Files.writeString(workspace.resolve("hit.txt"), "needle here\n");
        TextDiscoveryTools discovery = new TextDiscoveryTools(workspace);

        String searchJson = discovery.invoke("search", "{\"pattern\":\"needle\",\"pathGlob\":\"*.txt\"}");
        assertThat(searchJson).contains("hit.txt").contains("needle");
        assertThat(discovery.invoke("search", "{\"pattern\":\"needle\"}")).contains("hit.txt");
        assertThatThrownBy(() -> discovery.invoke("list", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path rejected");
        assertThatThrownBy(() -> discovery.invoke("list", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path rejected");
        String listJson = discovery.invoke("list", "{\"path\":\".\"}");
        assertThat(listJson).contains("hit.txt");
        assertThat(discovery.search("needle", null).hits()).isNotEmpty();
        assertThat(discovery.search("needle", " ").hits()).isNotEmpty();
        assertThat(discovery.definitions()).extracting(def -> def.name()).containsExactly("search", "list");
        assertThatThrownBy(() -> discovery.invoke("read", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown tool");
    }

    @Test
    void workspaceFileToolsPathConstructorIsTheSharedReadSubmitImplementation() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("files"));
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        WorkspaceFileTools files = new WorkspaceFileTools(workspace);
        LocalizationTools.FileSlice slice = files.read("a.txt", 1, 10);
        assertThat(slice.lines()).containsExactly("hello");
        assertThat(files.validateSubmit(List.of("a.txt")).accepted()).isTrue();
    }

    private static Set<Class<?>> loadProductionTypes() throws Exception {
        Set<Class<?>> types = new HashSet<>();
        try (Stream<Path> files = Files.walk(ANALYSIS_DIR)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Path relative = ANALYSIS_DIR.relativize(file);
                String className = "io.github.patchatlas.analysis."
                        + relative.toString().replace('\\', '/').replace('/', '.')
                                .replaceAll("\\.java$", "");
                Class<?> type = Class.forName(className);
                if (Modifier.isPublic(type.getModifiers())) {
                    types.add(type);
                }
            }
        }
        return types;
    }
}
