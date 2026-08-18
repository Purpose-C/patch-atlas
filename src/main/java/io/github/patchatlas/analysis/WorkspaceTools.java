package io.github.patchatlas.analysis;

import java.util.List;

/**
 * 工作区读写缝：read 与 submit 只有一份实现，文本发现与图发现共用。
 */
public interface WorkspaceTools {

    LocalizationTools.FileSlice read(String path, Integer startLine, Integer span);

    LocalizationTools.SubmitDecision validateSubmit(List<String> paths);
}
