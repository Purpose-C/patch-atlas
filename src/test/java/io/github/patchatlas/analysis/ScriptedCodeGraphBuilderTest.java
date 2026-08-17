package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.analysis.CodeGraph.Edge;
import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.Node;
import io.github.patchatlas.analysis.CodeGraph.NodeKind;
import io.github.patchatlas.analysis.CodeGraph.SourceLocation;
import io.github.patchatlas.analysis.CodeGraph.UnresolvedKind;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 脚本化构建器直接返回喂入的图，不读工作区。 */
class ScriptedCodeGraphBuilderTest {

    @TempDir
    Path workspace;

    @Test
    void buildReturnsFedGraphWithoutReadingWorkspace() {
        Node file = new Node(
                "file:src/A.java",
                NodeKind.FILE,
                "src/A.java",
                new SourceLocation("src/A.java", 1));
        Node type = new Node(
                "type:a.A", NodeKind.TYPE, "a.A", new SourceLocation("src/A.java", 3));
        Edge declares = new Edge(
                EdgeKind.DECLARES,
                ImpactConfidence.CONFIRMED,
                file,
                type,
                new SourceLocation("src/A.java", 3),
                null,
                List.of());
        CodeGraph fed = new CodeGraph("rev-1", List.of(file, type), List.of(declares));
        CodeGraphBuilder builder = new ScriptedCodeGraphBuilder(fed);

        CodeGraph built = builder.build(workspace, "rev-1");

        assertThat(built).isSameAs(fed);
        assertThat(built.schemaVersion()).isEqualTo(CodeGraph.SCHEMA_VERSION);
        assertThat(built.edges().getFirst().confidence()).isEqualTo(ImpactConfidence.CONFIRMED);
        assertThat(built.edges().getFirst().unresolvedKind()).isNull();
        assertThat(UnresolvedKind.REFLECTION).isNotNull();
    }
}
