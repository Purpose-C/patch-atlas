package io.github.patchatlas.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.patchatlas.replay.TargetTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 从 Candidate Test Patch 机械推导 {@link TargetTest}。
 *
 * <p>恰好一个新增的普通 {@code @Test} 方法才接受；歧义、嵌套、参数化或只改方法体一律拒绝，不猜测。
 */
public final class TargetTestDeriver {

    private static final String DUMMY_TYPE = "__PatchAtlasAdded";
    private static final Pattern SAFE_CLASS =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern SAFE_METHOD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    public sealed interface Result permits Result.Derived, Result.Rejected {
        record Derived(TargetTest targetTest) implements Result {
            public Derived {
                Objects.requireNonNull(targetTest, "targetTest");
            }
        }

        record Rejected(PatchRejectionCategory category, String reason) implements Result {
            public Rejected {
                Objects.requireNonNull(category, "category");
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    public Result derive(String patchText) {
        UnifiedDiffParser.ParseOutcome parsed = UnifiedDiffParser.parse(patchText);
        if (!parsed.isOk()) {
            return new Result.Rejected(parsed.category(), parsed.reason());
        }
        List<Discovered> found = new ArrayList<>();
        boolean bodyOnly = false;
        for (ParsedFileDiff file : parsed.files()) {
            FileScan scan = scanFile(file);
            if (scan.hardReject() != null) {
                return reject(scan.hardReject());
            }
            found.addAll(scan.found());
            bodyOnly = bodyOnly || scan.bodyOnly();
        }
        if (found.size() > 1) {
            return reject("补丁新增了 " + found.size() + " 个测试方法，无法确定目标");
        }
        if (found.size() == 1) {
            Discovered one = found.getFirst();
            if (one.nested()) {
                return reject("补丁新增了嵌套或内部类测试，无法确定目标");
            }
            if (one.parameterized()) {
                return reject("补丁新增了参数化测试，无法确定目标");
            }
            if (!SAFE_CLASS.matcher(one.className()).matches() || one.className().contains("$")) {
                return reject("推导出的测试类名不安全，无法确定目标");
            }
            if (!SAFE_METHOD.matcher(one.methodName()).matches()) {
                return reject("推导出的测试方法名不安全，无法确定目标");
            }
            return new Result.Derived(new TargetTest(one.className(), one.methodName()));
        }
        if (bodyOnly) {
            return reject("补丁只改了已有测试方法体，无法确定目标");
        }
        return reject("补丁新增了 0 个测试方法，无法确定目标");
    }

    private FileScan scanFile(ParsedFileDiff file) {
        Optional<String> pathClass = classNameFromTestPath(file.path());
        if (pathClass.isEmpty()) {
            return FileScan.ofHardReject("补丁路径不是测试源码，无法确定目标");
        }
        if (file.kind() == ParsedFileDiff.Kind.CREATE) {
            return scanCreate(file, pathClass.orElseThrow());
        }
        return scanModify(file, pathClass.orElseThrow());
    }

    private FileScan scanCreate(ParsedFileDiff file, String pathClass) {
        Optional<CompilationUnit> unit = parse(String.join("\n", file.addedLines()));
        if (unit.isEmpty()) {
            return FileScan.ofHardReject("补丁中的测试文件无法解析，无法确定目标");
        }
        List<Discovered> found = collect(unit.orElseThrow(), pathClass, false);
        for (Discovered method : found) {
            if (!pathClass.equals(method.className()) && !method.nested()) {
                return FileScan.ofHardReject("测试类名与文件路径不一致，无法确定目标");
            }
        }
        return FileScan.ofFound(found);
    }

    private FileScan scanModify(ParsedFileDiff file, String pathClass) {
        String added = String.join("\n", file.addedLines());
        Optional<CompilationUnit> wrapped = parse("class " + DUMMY_TYPE + " {\n" + added + "\n}\n");
        if (wrapped.isEmpty()) {
            return FileScan.ofBodyOnly();
        }
        return FileScan.ofFound(collect(wrapped.orElseThrow(), pathClass, true));
    }

    private List<Discovered> collect(CompilationUnit unit, String fallbackClass, boolean dummyRoot) {
        List<Discovered> found = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            String fqcn = dummyRoot ? fallbackClass : fqcn(unit, type);
            collectType(type, fqcn, false, found);
        }
        return found;
    }

    private static void collectType(
            TypeDeclaration<?> type, String fqcn, boolean nested, List<Discovered> found) {
        for (MethodDeclaration method : type.getMethods()) {
            Kind kind = testKind(method);
            if (kind == Kind.NONE) {
                continue;
            }
            found.add(new Discovered(fqcn, method.getNameAsString(), nested, kind == Kind.PARAMETERIZED));
        }
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nestedType) {
                collectType(nestedType, fqcn + "." + nestedType.getNameAsString(), true, found);
            }
        }
    }

    private static Kind testKind(MethodDeclaration method) {
        boolean ordinary = false;
        boolean parameterized = false;
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getName().getIdentifier();
            if ("ParameterizedTest".equals(name)
                    || "RepeatedTest".equals(name)
                    || "TestFactory".equals(name)
                    || "TestTemplate".equals(name)) {
                parameterized = true;
            } else if ("Test".equals(name)) {
                ordinary = true;
            }
        }
        if (parameterized) {
            return Kind.PARAMETERIZED;
        }
        if (ordinary) {
            return Kind.ORDINARY;
        }
        return Kind.NONE;
    }

    private Optional<CompilationUnit> parse(String source) {
        ParseResult<CompilationUnit> parsed = parser.parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            return Optional.empty();
        }
        return parsed.getResult();
    }

    private static String fqcn(CompilationUnit unit, TypeDeclaration<?> type) {
        String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String simple = type.getNameAsString();
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    private static Optional<String> classNameFromTestPath(String path) {
        String marker = "src/test/java/";
        int index = path.indexOf(marker);
        if (index < 0 || !path.endsWith(".java")) {
            return Optional.empty();
        }
        String relative = path.substring(index + marker.length(), path.length() - ".java".length());
        if (relative.isEmpty() || relative.contains("..")) {
            return Optional.empty();
        }
        return Optional.of(relative.replace('/', '.'));
    }

    private static Result.Rejected reject(String reason) {
        return new Result.Rejected(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE, reason);
    }

    private enum Kind {
        NONE,
        ORDINARY,
        PARAMETERIZED
    }

    private record Discovered(String className, String methodName, boolean nested, boolean parameterized) {}

    private record FileScan(List<Discovered> found, boolean bodyOnly, String hardReject) {
        static FileScan ofFound(List<Discovered> found) {
            return new FileScan(List.copyOf(found), false, null);
        }

        static FileScan ofBodyOnly() {
            return new FileScan(List.of(), true, null);
        }

        static FileScan ofHardReject(String reason) {
            return new FileScan(List.of(), false, reason);
        }
    }
}
