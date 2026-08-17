package io.github.patchatlas.analysis;

import java.nio.file.Path;
import java.util.Objects;

/** 测试用代码关系图构建器：直接返回预先编好的图，不解析工作区。 */
public final class ScriptedCodeGraphBuilder implements CodeGraphBuilder {

    private final CodeGraph graph;

    public ScriptedCodeGraphBuilder(CodeGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    @Override
    public CodeGraph build(Path workspace, String revision) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(revision, "revision");
        return graph;
    }
}
