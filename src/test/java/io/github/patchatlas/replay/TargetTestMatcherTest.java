package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetTestMatcherTest {

    private final TargetTestMatcher matcher = new TargetTestMatcher();
    private final TargetTest target = new TargetTest("com.example.BugTest", "detectsBug");

    @Test
    void matchesExactlyOnePassedTarget() {
        TestReport report = new TestReport(List.of(
                passed("com.example.BugTest", "detectsBug"),
                passed("com.example.OtherTest", "ok")));

        assertThat(matcher.match(report, target)).isEqualTo(TargetTestMatch.MATCHED_PASSED);
    }

    @Test
    void matchesExactlyOneFailedTarget() {
        TestReport report = new TestReport(List.of(failed("com.example.BugTest", "detectsBug")));

        assertThat(matcher.match(report, target)).isEqualTo(TargetTestMatch.MATCHED_FAILED);
    }

    @Test
    void missingTargetIsInvalidEvenIfReportHasOtherPasses() {
        TestReport report = new TestReport(List.of(passed("com.example.OtherTest", "ok")));

        assertThat(matcher.match(report, target)).isEqualTo(TargetTestMatch.MISSING);
    }

    @Test
    void emptyReportIsMissingTarget() {
        assertThat(matcher.match(TestReport.empty(), target)).isEqualTo(TargetTestMatch.MISSING);
    }

    @Test
    void duplicateTargetNamesAreInvalid() {
        TestReport report = new TestReport(List.of(
                failed("com.example.BugTest", "detectsBug"),
                failed("com.example.BugTest", "detectsBug")));

        assertThat(matcher.match(report, target)).isEqualTo(TargetTestMatch.DUPLICATE);
    }

    @Test
    void skippedTargetIsInvalid() {
        TestReport report = new TestReport(List.of(
                new TestCaseResult(
                        "com.example.BugTest",
                        "detectsBug",
                        Duration.ZERO,
                        TestCaseStatus.SKIPPED,
                        null,
                        "disabled")));

        assertThat(matcher.match(report, target)).isEqualTo(TargetTestMatch.SKIPPED);
    }

    @Test
    void errorTargetIsInvalid() {
        TestReport report = new TestReport(List.of(errored("com.example.BugTest", "detectsBug")));

        assertThat(matcher.match(report, target)).isEqualTo(TargetTestMatch.ERROR);
    }

    @Test
    void accompanyingFailureMakesEvidenceInvalid() {
        TestReport report = new TestReport(List.of(
                failed("com.example.BugTest", "detectsBug"),
                failed("com.example.OtherTest", "alsoFails")));

        assertThat(matcher.match(report, target)).isEqualTo(TargetTestMatch.ACCOMPANYING_FAILURES);
    }

    @Test
    void accompanyingErrorMakesEvidenceInvalidEvenWhenTargetPassed() {
        TestReport report = new TestReport(List.of(
                passed("com.example.BugTest", "detectsBug"),
                errored("com.example.OtherTest", "boom")));

        assertThat(matcher.match(report, target)).isEqualTo(TargetTestMatch.ACCOMPANYING_FAILURES);
    }

    @Test
    void matchingRequiresBothClassAndMethod() {
        TestReport report = new TestReport(List.of(failed("com.example.BugTest", "otherMethod")));

        assertThat(matcher.match(report, target)).isEqualTo(TargetTestMatch.MISSING);
    }

    private static TestCaseResult passed(String className, String method) {
        return new TestCaseResult(
                className, method, Duration.ofMillis(1), TestCaseStatus.PASSED, null, null);
    }

    private static TestCaseResult failed(String className, String method) {
        return new TestCaseResult(
                className,
                method,
                Duration.ofMillis(2),
                TestCaseStatus.FAILED,
                "org.opentest4j.AssertionFailedError",
                "expected");
    }

    private static TestCaseResult errored(String className, String method) {
        return new TestCaseResult(
                className,
                method,
                Duration.ofMillis(2),
                TestCaseStatus.ERROR,
                "java.lang.NullPointerException",
                "npe");
    }
}
