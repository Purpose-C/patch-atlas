package io.github.patchatlas.agent;

import java.util.Objects;

/**
 * 确定性 Fake adapter：结果由构造时显式给定，不读网络/环境变量/时间/随机数。
 */
public final class FakeTestGenerator implements TestGenerator {

    private final GenerationResult result;

    public FakeTestGenerator(GenerationResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public GenerationResult generate(GenerationInput input) {
        Objects.requireNonNull(input, "input");
        return result;
    }
}
