package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireReportParserTest {

    private final SurefireReportParser parser = new SurefireReportParser();

    @TempDir
    Path tempDir;

    @Test
    void parsesAllPassingTestcases() throws IOException {
        writeReport(
                "TEST-PassingTest.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.PassingTest" time="0.012" tests="2" errors="0" skipped="0" failures="0">
                  <testcase name="first" classname="com.example.PassingTest" time="0.004"/>
                  <testcase name="second" classname="com.example.PassingTest" time="0.008"/>
                </testsuite>
                """);

        TestReport report = parser.parse(tempDir);

        assertThat(report.testCases()).hasSize(2);
        assertThat(report.testCases())
                .allMatch(c -> c.status() == TestCaseStatus.PASSED)
                .allMatch(c -> c.exceptionType() == null)
                .allMatch(c -> c.message() == null);
        assertThat(report.testCases().getFirst())
                .satisfies(c -> {
                    assertThat(c.className()).isEqualTo("com.example.PassingTest");
                    assertThat(c.methodName()).isEqualTo("first");
                    assertThat(c.elapsed()).isEqualTo(Duration.ofMillis(4));
                });
        assertThat(report.count(TestCaseStatus.PASSED)).isEqualTo(2);
    }

    @Test
    void parsesAssertionFailureFromFailureElement() throws IOException {
        writeReport(
                "TEST-FailingTest.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.FailingTest" tests="1" errors="0" skipped="0" failures="1">
                  <testcase name="detectsOffByOne" classname="com.example.FailingTest" time="0.021">
                    <failure message="expected: &lt;c&gt; but was: &lt;b&gt;" type="org.opentest4j.AssertionFailedError">
                stack
                    </failure>
                  </testcase>
                </testsuite>
                """);

        TestReport report = parser.parse(tempDir);

        assertThat(report.testCases()).hasSize(1);
        TestCaseResult caseResult = report.testCases().getFirst();
        assertThat(caseResult.status()).isEqualTo(TestCaseStatus.FAILED);
        assertThat(caseResult.exceptionType()).isEqualTo("org.opentest4j.AssertionFailedError");
        assertThat(caseResult.message()).isEqualTo("expected: <c> but was: <b>");
        assertThat(caseResult.elapsed()).isEqualTo(Duration.ofMillis(21));
    }

    @Test
    void parsesTestErrorFromErrorElement() throws IOException {
        writeReport(
                "TEST-ErrorTest.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.ErrorTest" tests="1" errors="1" skipped="0" failures="0">
                  <testcase name="blowsUp" classname="com.example.ErrorTest" time="0.003">
                    <error message="boom" type="java.lang.IllegalStateException">
                java.lang.IllegalStateException: boom
                    </error>
                  </testcase>
                </testsuite>
                """);

        TestCaseResult caseResult = parser.parse(tempDir).testCases().getFirst();

        assertThat(caseResult.status()).isEqualTo(TestCaseStatus.ERROR);
        assertThat(caseResult.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(caseResult.message()).isEqualTo("boom");
    }

    @Test
    void parsesSkippedTestcase() throws IOException {
        writeReport(
                "TEST-SkippedTest.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.SkippedTest" tests="1" errors="0" skipped="1" failures="0">
                  <testcase name="notYet" classname="com.example.SkippedTest" time="0">
                    <skipped message="disabled"/>
                  </testcase>
                </testsuite>
                """);

        TestCaseResult caseResult = parser.parse(tempDir).testCases().getFirst();

        assertThat(caseResult.status()).isEqualTo(TestCaseStatus.SKIPPED);
        assertThat(caseResult.message()).isEqualTo("disabled");
    }

    @Test
    void aggregatesTestcasesAcrossMultipleXmlFiles() throws IOException {
        writeReport(
                "TEST-A.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.ATest" tests="1" errors="0" skipped="0" failures="0">
                  <testcase name="a" classname="com.example.ATest" time="0.001"/>
                </testsuite>
                """);
        writeReport(
                "TEST-B.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.BTest" tests="1" errors="0" skipped="0" failures="1">
                  <testcase name="b" classname="com.example.BTest" time="0.002">
                    <failure message="nope" type="org.opentest4j.AssertionFailedError"/>
                  </testcase>
                </testsuite>
                """);
        Files.writeString(tempDir.resolve("readme.txt"), "ignored", StandardCharsets.UTF_8);

        TestReport report = parser.parse(tempDir);

        assertThat(report.testCases())
                .extracting(TestCaseResult::methodName)
                .containsExactly("a", "b");
        assertThat(report.count(TestCaseStatus.PASSED)).isEqualTo(1);
        assertThat(report.count(TestCaseStatus.FAILED)).isEqualTo(1);
    }

    @Test
    void returnsEmptyReportWhenDirectoryIsMissing() {
        Path missing = tempDir.resolve("no-such-surefire-reports");

        TestReport report = parser.parse(missing);

        assertThat(report.testCases()).isEmpty();
        assertThat(report.totalCount()).isZero();
    }

    @Test
    void returnsEmptyReportWhenDirectoryHasNoSurefireXml() throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "not a report", StandardCharsets.UTF_8);

        assertThat(parser.parse(tempDir).testCases()).isEmpty();
    }

    @Test
    void rejectsPathThatExistsButIsNotADirectory() throws IOException {
        Path file = tempDir.resolve("not-a-dir");
        Files.writeString(file, "x", StandardCharsets.UTF_8);

        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(file));
    }

    @Test
    void failsFastOnCorruptXml() throws IOException {
        writeReport("TEST-Broken.xml", "<testsuite><not-closed");

        assertThatThrownBy(() -> parser.parse(tempDir))
                .isInstanceOf(SurefireReportParseException.class)
                .hasMessageContaining("TEST-Broken.xml");
    }

    @Test
    void boundsFailureMessageLength() throws IOException {
        String huge = "x".repeat(SurefireReportParser.MAX_MESSAGE_CHARS + 500);
        writeReport(
                "TEST-Huge.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.HugeTest" tests="1" errors="0" skipped="0" failures="1">
                  <testcase name="loud" classname="com.example.HugeTest" time="0.001">
                    <failure message="%s" type="org.opentest4j.AssertionFailedError"/>
                  </testcase>
                </testsuite>
                """
                        .formatted(huge));

        String message = parser.parse(tempDir).testCases().getFirst().message();

        assertThat(message).hasSize(SurefireReportParser.MAX_MESSAGE_CHARS);
        assertThat(message).startsWith("x".repeat(32));
    }

    @Test
    void doesNotExpandExternalEntitiesInUntrustedReports() throws IOException {
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET", StandardCharsets.UTF_8);
        writeReport(
                "TEST-Xxe.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE testsuite [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <testsuite name="com.example.XxeTest" tests="1" errors="0" skipped="0" failures="1">
                  <testcase name="probe" classname="com.example.XxeTest" time="0.001">
                    <failure message="&xxe;" type="org.opentest4j.AssertionFailedError"/>
                  </testcase>
                </testsuite>
                """
                        .formatted(secret.toUri()));

        // Either parse fails (DOCTYPE disabled) or entity is not expanded into message.
        try {
            List<TestCaseResult> cases = parser.parse(tempDir).testCases();
            assertThat(cases).isNotEmpty();
            assertThat(cases.getFirst().message()).doesNotContain("TOP-SECRET");
        } catch (SurefireReportParseException expected) {
            assertThat(expected.getMessage()).contains("TEST-Xxe.xml");
        }
    }

    @Test
    void rejectsSymlinkedReportFileWithoutFollowingIt() throws IOException {
        // 目标必须是合法 Surefire XML：旧实现若跟随链接会解析成功并返回 PASS，
        // 只有拒绝 symlink 时才会以 SecurityException 失败——测试才有 RED 能力。
        Path outsideReport = tempDir.resolve("outside-TEST-Passed.xml");
        Files.writeString(outsideReport, minimalPassingXml(), StandardCharsets.UTF_8);
        Path reports = tempDir.resolve("surefire-reports");
        Files.createDirectory(reports);
        Path link = reports.resolve("TEST-Evil.xml");
        Files.createSymbolicLink(link, outsideReport);

        assertThatThrownBy(() -> parser.parse(reports))
                .isInstanceOf(SurefireReportParseException.class)
                .hasMessageContaining("TEST-Evil.xml")
                .cause()
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsWhenReportFileCountWouldExceedLimit() throws IOException {
        Path reports = tempDir.resolve("many-reports");
        Files.createDirectory(reports);
        for (int i = 0; i < SurefireReportParser.MAX_REPORT_FILES + 1; i++) {
            writeReportTo(reports, "TEST-%03d.xml".formatted(i), minimalPassingXml());
        }

        assertThatThrownBy(() -> parser.parse(reports))
                .isInstanceOf(SurefireReportParseException.class)
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many surefire report files");
    }

    @Test
    void rejectsSymlinkedReportsDirectory() throws IOException {
        Path realReports = tempDir.resolve("real-reports");
        Files.createDirectory(realReports);
        writeReportTo(realReports, "TEST-Ok.xml", minimalPassingXml());
        Path linkDir = tempDir.resolve("linked-reports");
        Files.createSymbolicLink(linkDir, realReports);

        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(linkDir));
    }

    @Test
    void rejectsOversizedReportFileBeforeBuildingDom() throws IOException {
        Path huge = tempDir.resolve("TEST-TooBig.xml");
        try (var out = Files.newOutputStream(huge)) {
            byte[] chunk = new byte[8192];
            long remaining = SurefireReportParser.MAX_REPORT_FILE_BYTES + 1;
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                out.write(chunk, 0, n);
                remaining -= n;
            }
        }

        assertThatThrownBy(() -> parser.parse(tempDir))
                .isInstanceOf(SurefireReportParseException.class)
                .hasMessageContaining("TEST-TooBig.xml")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("per-file size limit");
    }

    private void writeReport(String fileName, String xml) throws IOException {
        writeReportTo(tempDir, fileName, xml);
    }

    private static void writeReportTo(Path dir, String fileName, String xml) throws IOException {
        Files.writeString(dir.resolve(fileName), xml, StandardCharsets.UTF_8);
    }

    private static String minimalPassingXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.Ok" tests="1" errors="0" skipped="0" failures="0">
                  <testcase name="ok" classname="com.example.Ok" time="0.001"/>
                </testsuite>
                """;
    }
}
