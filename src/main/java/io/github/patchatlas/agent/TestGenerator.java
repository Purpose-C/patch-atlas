package io.github.patchatlas.agent;

/**
 * 候选测试生成 seam。仅有 Fake / 真实模型两种 adapter；参数类型上不得出现 Oracle Data。
 */
public interface TestGenerator {

    GenerationResult generate(GenerationInput input);
}
