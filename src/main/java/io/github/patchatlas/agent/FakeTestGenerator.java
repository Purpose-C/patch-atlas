package io.github.patchatlas.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 确定性 Fake：按调用序号返回脚本结果；不读网络/环境变量/时间/随机数/Fixed。
 */
public class FakeTestGenerator implements TestGenerator {

    public static final String FIXTURE_MODEL = "fixture-v1";

    private final GeneratorIdentity identity;
    private final List<GenerationResult> script;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<GenerationRequest> capturedRequests =
            Collections.synchronizedList(new ArrayList<>());

    public FakeTestGenerator(GenerationResult single) {
        this(List.of(Objects.requireNonNull(single, "single")));
    }

    public FakeTestGenerator(List<GenerationResult> script) {
        this(GeneratorIdentity.fake(FIXTURE_MODEL), script);
    }

    public FakeTestGenerator(GeneratorIdentity identity, List<GenerationResult> script) {
        this.identity = Objects.requireNonNull(identity, "identity");
        if (!"fake".equals(identity.provider())) {
            throw new IllegalArgumentException("FakeTestGenerator identity must use fake provider");
        }
        Objects.requireNonNull(script, "script");
        if (script.isEmpty()) {
            throw new IllegalArgumentException("script must not be empty");
        }
        this.script = List.copyOf(script);
    }

    @Override
    public GeneratorIdentity identity() {
        return identity;
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        capturedRequests.add(request);
        int index = calls.getAndIncrement();
        if (index >= script.size()) {
            return script.getLast();
        }
        return script.get(index);
    }

    public int callCount() {
        return calls.get();
    }

    /** 捕获的全部请求（按调用序）。 */
    public List<GenerationRequest> capturedRequests() {
        return List.copyOf(capturedRequests);
    }

    /** 按 ordinal 1..n 配置脚本（测试便利）。 */
    public static FakeTestGenerator of(GenerationResult... results) {
        List<GenerationResult> list = new ArrayList<>();
        for (GenerationResult r : results) {
            list.add(r);
        }
        return new FakeTestGenerator(list);
    }
}
