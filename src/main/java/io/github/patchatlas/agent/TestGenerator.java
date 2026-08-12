package io.github.patchatlas.agent;

/**
 * 候选测试生成 seam：一次逻辑模型调用。
 *
 * <p>Fake / Spring AI 两种 adapter；参数类型上不得出现 Oracle Data。
 */
public interface TestGenerator {

    GeneratorIdentity identity();

    GenerationResult generate(GenerationRequest request);
}
