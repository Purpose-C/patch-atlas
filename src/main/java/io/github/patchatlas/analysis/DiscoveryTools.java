package io.github.patchatlas.analysis;

import java.util.List;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 发现工具缝：文本臂与图臂各提供两个发现工具；read 与 submit 不在此层。
 */
public interface DiscoveryTools {

    List<ToolDefinition> definitions();

    String invoke(String name, String args);
}
