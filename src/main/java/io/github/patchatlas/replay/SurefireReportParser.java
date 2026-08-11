package io.github.patchatlas.replay;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 将 Maven Surefire 报告目录解析为中立 {@link TestReport}。
 *
 * <p>实现细节（DOM）不出现在公开类型签名之外的模块 API 中；调用方只依赖本类与事实 record。
 * 报告目录与条目来自不可信构建产物：拒绝符号链接，并在建 DOM 前限制体积。
 */
public final class SurefireReportParser {

    public static final int MAX_MESSAGE_CHARS = 2048;

    /** 单个 Surefire XML 在解析进 DOM 前的字节上限。 */
    public static final long MAX_REPORT_FILE_BYTES = 2L * 1024 * 1024;

    /** 一次 parse 可累计读取的报告字节上限。 */
    public static final long MAX_REPORT_TOTAL_BYTES = 8L * 1024 * 1024;

    /** 一次 parse 可接受的报告文件数量上限。 */
    public static final int MAX_REPORT_FILES = 256;

    public TestReport parse(Path reportsDirectory) {
        Objects.requireNonNull(reportsDirectory, "reportsDirectory");
        if (!Files.exists(reportsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return TestReport.empty();
        }
        if (Files.isSymbolicLink(reportsDirectory)) {
            throw new IllegalArgumentException(
                    "reportsDirectory must not be a symbolic link: " + reportsDirectory);
        }
        if (!Files.isDirectory(reportsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("reportsDirectory must be a directory: " + reportsDirectory);
        }

        Path reportsRoot = reportsDirectory.toAbsolutePath().normalize();
        List<Path> reportFiles = listSurefireXmlFiles(reportsRoot);
        if (reportFiles.isEmpty()) {
            return TestReport.empty();
        }

        List<TestCaseResult> cases = new ArrayList<>();
        long totalBytes = 0L;
        for (Path reportFile : reportFiles) {
            totalBytes = assertSafeAndAccountSize(reportsRoot, reportFile, totalBytes);
            cases.addAll(parseFile(reportFile));
        }
        return new TestReport(cases);
    }

    private List<Path> listSurefireXmlFiles(Path reportsDirectory) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDirectory, "TEST-*.xml")) {
            for (Path path : stream) {
                if (Files.isSymbolicLink(path)) {
                    throw new SurefireReportParseException(
                            path.getFileName().toString(),
                            new SecurityException("surefire report must not be a symbolic link"));
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                // 在加入第 MAX_REPORT_FILES+1 条路径前立即拒绝，避免海量路径先堆进内存。
                if (files.size() >= MAX_REPORT_FILES) {
                    throw new SurefireReportParseException(
                            reportsDirectory.getFileName().toString(),
                            new IllegalArgumentException(
                                    "too many surefire report files: > " + MAX_REPORT_FILES));
                }
                files.add(path);
            }
        } catch (IOException ex) {
            throw new SurefireReportParseException(reportsDirectory.getFileName().toString(), ex);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    private static long assertSafeAndAccountSize(Path reportsRoot, Path reportFile, long totalBytes) {
        if (Files.isSymbolicLink(reportFile)) {
            throw new SurefireReportParseException(
                    reportFile.getFileName().toString(),
                    new SecurityException("surefire report must not be a symbolic link"));
        }
        Path normalized = reportFile.toAbsolutePath().normalize();
        if (!normalized.startsWith(reportsRoot)) {
            throw new SurefireReportParseException(
                    reportFile.getFileName().toString(),
                    new SecurityException("surefire report path escapes reports directory"));
        }
        long size;
        try {
            size = Files.size(reportFile);
        } catch (IOException ex) {
            throw new SurefireReportParseException(reportFile.getFileName().toString(), ex);
        }
        if (size > MAX_REPORT_FILE_BYTES) {
            throw new SurefireReportParseException(
                    reportFile.getFileName().toString(),
                    new IllegalArgumentException(
                            "surefire report exceeds per-file size limit: "
                                    + size
                                    + " > "
                                    + MAX_REPORT_FILE_BYTES));
        }
        long nextTotal = totalBytes + size;
        if (nextTotal > MAX_REPORT_TOTAL_BYTES) {
            throw new SurefireReportParseException(
                    reportFile.getFileName().toString(),
                    new IllegalArgumentException(
                            "surefire reports exceed total size limit: "
                                    + nextTotal
                                    + " > "
                                    + MAX_REPORT_TOTAL_BYTES));
        }
        return nextTotal;
    }

    private List<TestCaseResult> parseFile(Path reportFile) {
        Document document;
        try (InputStream input = Files.newInputStream(reportFile)) {
            document = newSecureDocumentBuilderFactory().newDocumentBuilder().parse(input);
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new SurefireReportParseException(reportFile.getFileName().toString(), ex);
        }

        Element root = document.getDocumentElement();
        if (root == null || !"testsuite".equals(root.getTagName())) {
            throw new SurefireReportParseException(
                    reportFile.getFileName().toString(),
                    new IllegalArgumentException("root element must be testsuite"));
        }

        List<TestCaseResult> cases = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "testcase".equals(node.getNodeName())) {
                cases.add(toCaseResult((Element) node));
            }
        }
        return cases;
    }

    private TestCaseResult toCaseResult(Element testcase) {
        String className = attributeOrEmpty(testcase, "classname");
        String methodName = attributeOrEmpty(testcase, "name");
        Duration elapsed = parseSeconds(attributeOrEmpty(testcase, "time"));

        Element failure = firstChild(testcase, "failure");
        if (failure != null) {
            return new TestCaseResult(
                    className,
                    methodName,
                    elapsed,
                    TestCaseStatus.FAILED,
                    emptyToNull(attributeOrEmpty(failure, "type")),
                    boundMessage(failure));
        }

        Element error = firstChild(testcase, "error");
        if (error != null) {
            return new TestCaseResult(
                    className,
                    methodName,
                    elapsed,
                    TestCaseStatus.ERROR,
                    emptyToNull(attributeOrEmpty(error, "type")),
                    boundMessage(error));
        }

        Element skipped = firstChild(testcase, "skipped");
        if (skipped != null) {
            return new TestCaseResult(
                    className,
                    methodName,
                    elapsed,
                    TestCaseStatus.SKIPPED,
                    null,
                    boundMessage(skipped));
        }

        return new TestCaseResult(className, methodName, elapsed, TestCaseStatus.PASSED, null, null);
    }

    private static Element firstChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String boundMessage(Element element) {
        // 只用 message 属性，避免把完整堆栈 textContent 整段载入内存。
        String message = attributeOrEmpty(element, "message");
        if (message.isEmpty()) {
            return null;
        }
        if (message.length() <= MAX_MESSAGE_CHARS) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_CHARS);
    }

    private static Duration parseSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Duration.ZERO;
        }
        try {
            double seconds = Double.parseDouble(raw.trim());
            if (seconds < 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) {
                return Duration.ZERO;
            }
            long nanos = Math.round(seconds * 1_000_000_000L);
            return Duration.ofNanos(nanos);
        } catch (NumberFormatException ex) {
            return Duration.ZERO;
        }
    }

    private static String attributeOrEmpty(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static DocumentBuilderFactory newSecureDocumentBuilderFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setIgnoringComments(true);
        return factory;
    }
}
