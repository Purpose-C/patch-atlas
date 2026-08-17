package io.github.patchatlas.analysis;

import java.nio.file.Path;

/**
 * 代码关系图构建 seam。图是 (workspace, revision) 的纯函数，不含 Oracle Data。
 */
public interface CodeGraphBuilder {

    CodeGraph build(Path workspace, String revision);
}
