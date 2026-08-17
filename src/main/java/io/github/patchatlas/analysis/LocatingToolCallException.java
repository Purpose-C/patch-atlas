package io.github.patchatlas.analysis;

/** 定位工具循环收到了不支持的 tool_calls 形状（空或超过并行上限）。 */
public final class LocatingToolCallException extends IllegalStateException {

    public LocatingToolCallException(String message) {
        super(message);
    }
}
