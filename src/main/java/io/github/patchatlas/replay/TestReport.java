package io.github.patchatlas.replay;

import java.util.List;
import java.util.Objects;

/** 一次 Maven 测试执行解析出的全部用例事实。 */
public record TestReport(List<TestCaseResult> testCases) {

    public TestReport {
        testCases = List.copyOf(Objects.requireNonNull(testCases, "testCases"));
    }

    public static TestReport empty() {
        return new TestReport(List.of());
    }

    public int totalCount() {
        return testCases.size();
    }

    public long count(TestCaseStatus status) {
        Objects.requireNonNull(status, "status");
        return testCases.stream().filter(c -> c.status() == status).count();
    }
}
