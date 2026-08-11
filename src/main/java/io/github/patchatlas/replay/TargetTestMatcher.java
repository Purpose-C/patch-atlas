package io.github.patchatlas.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 在 {@link TestReport} 中按完整类名 + 方法名精确匹配 Target Test。 */
public final class TargetTestMatcher {

    public TargetTestMatch match(TestReport report, TargetTest target) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(target, "target");

        List<TestCaseResult> matches = new ArrayList<>();
        for (TestCaseResult testCase : report.testCases()) {
            if (target.className().equals(testCase.className())
                    && target.methodName().equals(testCase.methodName())) {
                matches.add(testCase);
            }
        }

        if (matches.isEmpty()) {
            return TargetTestMatch.MISSING;
        }
        if (matches.size() > 1) {
            return TargetTestMatch.DUPLICATE;
        }

        TestCaseResult matched = matches.getFirst();
        if (hasAccompanyingFailureOrError(report, target)) {
            return TargetTestMatch.ACCOMPANYING_FAILURES;
        }

        return switch (matched.status()) {
            case PASSED -> TargetTestMatch.MATCHED_PASSED;
            case FAILED -> TargetTestMatch.MATCHED_FAILED;
            case SKIPPED -> TargetTestMatch.SKIPPED;
            case ERROR -> TargetTestMatch.ERROR;
        };
    }

    private static boolean hasAccompanyingFailureOrError(TestReport report, TargetTest target) {
        for (TestCaseResult testCase : report.testCases()) {
            if (isTarget(testCase, target)) {
                continue;
            }
            if (testCase.status() == TestCaseStatus.FAILED || testCase.status() == TestCaseStatus.ERROR) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTarget(TestCaseResult testCase, TargetTest target) {
        return target.className().equals(testCase.className())
                && target.methodName().equals(testCase.methodName());
    }
}
