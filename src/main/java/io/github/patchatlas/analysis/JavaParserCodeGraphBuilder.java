package io.github.patchatlas.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import io.github.patchatlas.analysis.CodeGraph.Edge;
import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.Node;
import io.github.patchatlas.analysis.CodeGraph.NodeKind;
import io.github.patchatlas.analysis.CodeGraph.SourceLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 仅用仓库自身源码建符号表的代码关系图构建器。不读依赖 jar，不解析 Spring 类型。
 */
public final class JavaParserCodeGraphBuilder implements CodeGraphBuilder {

    public static final String PARSER_VERSION = "javaparser-3.26.4";

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    @Override
    public CodeGraph build(Path workspace, String revision) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(revision, "revision");
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        Map<String, TypeSymbol> types = new LinkedHashMap<>();
        try {
            for (Path file : javaFiles(workspace)) {
                CompilationUnit unit = parse(file);
                String relative = relativize(workspace, file);
                Node fileNode = new Node(
                        "file:" + relative,
                        NodeKind.FILE,
                        relative,
                        new SourceLocation(relative, 1));
                nodes.add(fileNode);
                FileContext context = FileContext.from(unit, relative, fileNode);
                for (TypeDeclaration<?> declaration : unit.getTypes()) {
                    collectType(declaration, context, null, types, nodes, edges);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("failed to scan Java sources", ex);
        }
        for (TypeSymbol type : types.values()) {
            resolveHeritage(type, types, edges);
            resolveFields(type, types);
        }
        for (TypeSymbol type : types.values()) {
            collectCalls(type, types, edges);
        }
        return new CodeGraph(revision, nodes, edges);
    }

    private CompilationUnit parse(Path file) {
        try {
            return parser.parse(file)
                    .getResult()
                    .orElseThrow(() -> new IllegalStateException("parse failed: " + file));
        } catch (IOException ex) {
            throw new IllegalStateException("parse failed: " + file, ex);
        }
    }

    private void collectType(
            TypeDeclaration<?> declaration,
            FileContext file,
            TypeSymbol enclosing,
            Map<String, TypeSymbol> types,
            List<Node> nodes,
            List<Edge> edges) {
        String simple = declaration.getNameAsString();
        String fqcn = enclosing == null ? file.qualify(simple) : enclosing.fqcn + "." + simple;
        int line = line(declaration);
        Node typeNode = new Node(
                "type:" + fqcn, NodeKind.TYPE, fqcn, new SourceLocation(file.relative, line));
        nodes.add(typeNode);
        Node parent = enclosing == null ? file.fileNode : enclosing.node;
        edges.add(declares(parent, typeNode, file.relative, line));

        TypeSymbol symbol = new TypeSymbol(fqcn, typeNode, file, isInterface(declaration), declaration);
        types.put(fqcn, symbol);
        if (enclosing == null) {
            file.topLevelSimpleNames.put(simple, fqcn);
        }
        collectMembers(declaration, symbol, nodes, edges, file);
        for (BodyDeclaration<?> member : declaration.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collectType(nested, file, symbol, types, nodes, edges);
            }
        }
    }

    private void resolveHeritage(TypeSymbol symbol, Map<String, TypeSymbol> types, List<Edge> edges) {
        TypeDeclaration<?> declaration = symbol.declaration;
        if (declaration instanceof ClassOrInterfaceDeclaration type) {
            for (ClassOrInterfaceType extended : type.getExtendedTypes()) {
                Optional<String> fqcn = symbol.file.resolveTypeName(extended, types);
                if (!type.isInterface()) {
                    fqcn.ifPresent(resolved -> symbol.superFqcn = resolved);
                } else {
                    fqcn.filter(types::containsKey).ifPresent(symbol.interfaces::add);
                }
                linkHeritage(
                        symbol,
                        extended,
                        EdgeKind.EXTENDS,
                        types,
                        edges);
            }
            for (ClassOrInterfaceType implemented : type.getImplementedTypes()) {
                symbol.file.resolveTypeName(implemented, types)
                        .filter(types::containsKey)
                        .ifPresent(symbol.interfaces::add);
                linkHeritage(symbol, implemented, EdgeKind.IMPLEMENTS, types, edges);
            }
        } else if (declaration instanceof EnumDeclaration enumerated) {
            for (ClassOrInterfaceType implemented : enumerated.getImplementedTypes()) {
                symbol.file.resolveTypeName(implemented, types)
                        .filter(types::containsKey)
                        .ifPresent(symbol.interfaces::add);
                linkHeritage(symbol, implemented, EdgeKind.IMPLEMENTS, types, edges);
            }
        } else if (declaration instanceof RecordDeclaration record) {
            for (ClassOrInterfaceType implemented : record.getImplementedTypes()) {
                symbol.file.resolveTypeName(implemented, types)
                        .filter(types::containsKey)
                        .ifPresent(symbol.interfaces::add);
                linkHeritage(symbol, implemented, EdgeKind.IMPLEMENTS, types, edges);
            }
        }
    }

    private void linkHeritage(
            TypeSymbol symbol,
            ClassOrInterfaceType referenced,
            EdgeKind kind,
            Map<String, TypeSymbol> types,
            List<Edge> edges) {
        Optional<String> fqcn = symbol.file.resolveTypeName(referenced, types);
        SourceLocation location = new SourceLocation(symbol.file.relative, line(referenced));
        if (fqcn.isPresent() && types.containsKey(fqcn.orElseThrow())) {
            edges.add(new Edge(
                    kind,
                    ImpactConfidence.CONFIRMED,
                    symbol.node,
                    types.get(fqcn.orElseThrow()).node,
                    location,
                    null,
                    List.of()));
            return;
        }
        String simple = referenced.getNameAsString();
        if ("Object".equals(simple) || "Record".equals(simple) || "Enum".equals(simple)) {
            return;
        }
        edges.add(new Edge(
                kind, ImpactConfidence.POSSIBLE, symbol.node, null, location, null, List.of()));
    }

    private void resolveFields(TypeSymbol symbol, Map<String, TypeSymbol> types) {
        for (Map.Entry<String, Type> field : symbol.fieldTypes.entrySet()) {
            symbol.file.resolveTypeName(field.getValue(), types)
                    .ifPresent(fqcn -> symbol.fields.put(field.getKey(), fqcn));
        }
    }

    private void collectMembers(
            TypeDeclaration<?> declaration,
            TypeSymbol symbol,
            List<Node> nodes,
            List<Edge> edges,
            FileContext file) {
        for (FieldDeclaration field : declaration.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                int line = line(variable);
                Node fieldNode = new Node(
                        "field:" + symbol.fqcn + "#" + variable.getNameAsString(),
                        NodeKind.FIELD,
                        symbol.fqcn + "#" + variable.getNameAsString(),
                        new SourceLocation(file.relative, line));
                nodes.add(fieldNode);
                edges.add(declares(symbol.node, fieldNode, file.relative, line));
                symbol.fieldTypes.put(variable.getNameAsString(), variable.getType());
            }
        }
        if (declaration instanceof ClassOrInterfaceDeclaration type) {
            for (MethodDeclaration method : type.getMethods()) {
                addMethod(symbol, method.getNameAsString(), method, nodes, edges, file);
            }
            for (ConstructorDeclaration constructor : type.getConstructors()) {
                addConstructor(symbol, constructor, nodes, edges, file);
            }
        } else if (declaration instanceof EnumDeclaration enumerated) {
            for (MethodDeclaration method : enumerated.getMethods()) {
                addMethod(symbol, method.getNameAsString(), method, nodes, edges, file);
            }
        } else if (declaration instanceof RecordDeclaration record) {
            for (MethodDeclaration method : record.getMethods()) {
                addMethod(symbol, method.getNameAsString(), method, nodes, edges, file);
            }
        }
    }

    private void addMethod(
            TypeSymbol symbol,
            String name,
            MethodDeclaration method,
            List<Node> nodes,
            List<Edge> edges,
            FileContext file) {
        int line = line(method);
        Node methodNode = new Node(
                "method:" + symbol.fqcn + "#" + name + ":" + line,
                NodeKind.METHOD,
                symbol.fqcn + "#" + name,
                new SourceLocation(file.relative, line));
        nodes.add(methodNode);
        edges.add(declares(symbol.node, methodNode, file.relative, line));
        symbol.methods.put(name, new MethodSymbol(methodNode, method));
    }

    private void addConstructor(
            TypeSymbol symbol,
            ConstructorDeclaration constructor,
            List<Node> nodes,
            List<Edge> edges,
            FileContext file) {
        int line = line(constructor);
        Node methodNode = new Node(
                "method:" + symbol.fqcn + "#<init>:" + line,
                NodeKind.METHOD,
                symbol.fqcn + "#<init>",
                new SourceLocation(file.relative, line));
        nodes.add(methodNode);
        edges.add(declares(symbol.node, methodNode, file.relative, line));
        symbol.constructors.add(new MethodSymbol(methodNode, constructor));
    }

    private void collectCalls(TypeSymbol type, Map<String, TypeSymbol> types, List<Edge> edges) {
        for (MethodSymbol method : type.methods.values()) {
            if (method.declaration instanceof MethodDeclaration declaration) {
                declaration.getBody().ifPresent(body -> {
                    Map<String, String> locals = new LinkedHashMap<>(type.fields);
                    for (Parameter parameter : declaration.getParameters()) {
                        type.file.resolveTypeName(parameter.getType(), types)
                                .ifPresent(fqcn -> locals.put(parameter.getNameAsString(), fqcn));
                    }
                    body.walk(VariableDeclarator.class, variable -> type.file
                            .resolveTypeName(variable.getType(), types)
                            .ifPresent(fqcn -> locals.put(variable.getNameAsString(), fqcn)));
                    body.walk(
                            MethodCallExpr.class,
                            call -> emitCall(type, method.node, call, locals, types, edges));
                });
            }
        }
        for (MethodSymbol constructor : type.constructors) {
            if (constructor.declaration instanceof ConstructorDeclaration declaration) {
                Map<String, String> locals = new LinkedHashMap<>(type.fields);
                for (Parameter parameter : declaration.getParameters()) {
                    type.file.resolveTypeName(parameter.getType(), types)
                            .ifPresent(fqcn -> locals.put(parameter.getNameAsString(), fqcn));
                }
                declaration.getBody().walk(VariableDeclarator.class, variable -> type.file
                        .resolveTypeName(variable.getType(), types)
                        .ifPresent(fqcn -> locals.put(variable.getNameAsString(), fqcn)));
                declaration
                        .getBody()
                        .walk(
                                MethodCallExpr.class,
                                call -> emitCall(
                                        type, constructor.node, call, locals, types, edges));
            }
        }
    }

    private void emitCall(
            TypeSymbol current,
            Node caller,
            MethodCallExpr call,
            Map<String, String> locals,
            Map<String, TypeSymbol> types,
            List<Edge> edges) {
        SourceLocation location = new SourceLocation(current.file.relative, line(call));
        Optional<String> receiver = resolveReceiver(call, current, locals, types);
        if (receiver.isEmpty() || !types.containsKey(receiver.orElseThrow())) {
            edges.add(new Edge(
                    EdgeKind.CALLS,
                    ImpactConfidence.POSSIBLE,
                    caller,
                    null,
                    location,
                    null,
                    List.of()));
            return;
        }
        TypeSymbol receiverType = types.get(receiver.orElseThrow());
        Node target = findMethod(receiverType, call.getNameAsString(), types);
        List<TypeSymbol> implementors = implementorsOf(receiverType, types);
        if (receiverType.isInterface && implementors.size() > 1) {
            List<Node> candidates = new ArrayList<>();
            for (TypeSymbol implementor : implementors) {
                Node candidate = findDeclaredMethod(implementor, call.getNameAsString());
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
            edges.add(new Edge(
                    EdgeKind.CALLS,
                    ImpactConfidence.POSSIBLE,
                    caller,
                    target,
                    location,
                    null,
                    candidates));
            return;
        }
        edges.add(new Edge(
                EdgeKind.CALLS,
                ImpactConfidence.CONFIRMED,
                caller,
                target,
                location,
                null,
                List.of()));
    }

    private Optional<String> resolveReceiver(
            MethodCallExpr call,
            TypeSymbol current,
            Map<String, String> locals,
            Map<String, TypeSymbol> types) {
        if (call.getScope().isEmpty()) {
            return findOwnerOf(current, call.getNameAsString(), types).map(type -> type.fqcn);
        }
        return resolveExpressionType(call.getScope().orElseThrow(), current, locals, types);
    }

    private Optional<String> resolveExpressionType(
            Expression expression,
            TypeSymbol current,
            Map<String, String> locals,
            Map<String, TypeSymbol> types) {
        if (expression instanceof ThisExpr) {
            return Optional.of(current.fqcn);
        }
        if (expression instanceof SuperExpr) {
            return Optional.ofNullable(current.superFqcn);
        }
        if (expression instanceof NameExpr name) {
            String identifier = name.getNameAsString();
            if (locals.containsKey(identifier)) {
                return Optional.of(locals.get(identifier));
            }
            return current.file.resolveSimpleName(identifier, types);
        }
        if (expression instanceof ObjectCreationExpr created) {
            return current.file.resolveTypeName(created.getType(), types);
        }
        if (expression instanceof FieldAccessExpr access) {
            Optional<String> owner =
                    resolveExpressionType(access.getScope(), current, locals, types);
            if (owner.isPresent() && types.containsKey(owner.orElseThrow())) {
                TypeSymbol ownerType = types.get(owner.orElseThrow());
                return Optional.ofNullable(ownerType.fields.get(access.getNameAsString()));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<TypeSymbol> findOwnerOf(
            TypeSymbol start, String methodName, Map<String, TypeSymbol> types) {
        TypeSymbol cursor = start;
        while (cursor != null) {
            if (cursor.methods.containsKey(methodName)) {
                return Optional.of(cursor);
            }
            cursor = cursor.superFqcn == null ? null : types.get(cursor.superFqcn);
        }
        return Optional.empty();
    }

    private List<TypeSymbol> implementorsOf(TypeSymbol iface, Map<String, TypeSymbol> types) {
        if (!iface.isInterface) {
            return List.of();
        }
        List<TypeSymbol> implementors = new ArrayList<>();
        for (TypeSymbol type : types.values()) {
            if (!type.isInterface && interfacesOf(type, types).contains(iface.fqcn)) {
                implementors.add(type);
            }
        }
        return implementors;
    }

    private List<String> interfacesOf(TypeSymbol type, Map<String, TypeSymbol> types) {
        List<String> found = new ArrayList<>();
        TypeSymbol cursor = type;
        while (cursor != null) {
            for (String iface : cursor.interfaces) {
                if (!found.contains(iface)) {
                    found.add(iface);
                    TypeSymbol interfaceType = types.get(iface);
                    if (interfaceType != null) {
                        for (String inherited : interfacesOf(interfaceType, types)) {
                            if (!found.contains(inherited)) {
                                found.add(inherited);
                            }
                        }
                    }
                }
            }
            cursor = cursor.superFqcn == null ? null : types.get(cursor.superFqcn);
        }
        return found;
    }

    private Node findDeclaredMethod(TypeSymbol type, String name) {
        MethodSymbol method = type.methods.get(name);
        return method == null ? null : method.node;
    }

    private Node findMethod(TypeSymbol type, String name, Map<String, TypeSymbol> types) {
        TypeSymbol cursor = type;
        while (cursor != null) {
            MethodSymbol method = cursor.methods.get(name);
            if (method != null) {
                return method.node;
            }
            for (String iface : cursor.interfaces) {
                TypeSymbol interfaceType = types.get(iface);
                if (interfaceType != null && interfaceType.methods.containsKey(name)) {
                    return interfaceType.methods.get(name).node;
                }
            }
            cursor = cursor.superFqcn == null ? null : types.get(cursor.superFqcn);
        }
        return type.node;
    }

    private static Edge declares(Node source, Node target, String relative, int line) {
        return new Edge(
                EdgeKind.DECLARES,
                ImpactConfidence.CONFIRMED,
                source,
                target,
                new SourceLocation(relative, line),
                null,
                List.of());
    }

    private static boolean isInterface(TypeDeclaration<?> declaration) {
        return declaration instanceof ClassOrInterfaceDeclaration type && type.isInterface();
    }

    private static int line(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(position -> position.line).orElse(1);
    }

    private static List<Path> javaFiles(Path workspace) throws IOException {
        try (Stream<Path> walk = Files.walk(workspace)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !ignored(workspace.relativize(path)))
                    .sorted()
                    .toList();
        }
    }

    private static boolean ignored(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if ("target".equals(name) || ".git".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String relativize(Path workspace, Path file) {
        return workspace.relativize(file).toString().replace('\\', '/');
    }

    private static final class FileContext {
        private final String relative;
        private final Node fileNode;
        private final String packageName;
        private final Map<String, String> imports = new LinkedHashMap<>();
        private final List<String> asterisks = new ArrayList<>();
        private final Map<String, String> topLevelSimpleNames = new LinkedHashMap<>();

        private FileContext(String relative, Node fileNode, String packageName) {
            this.relative = relative;
            this.fileNode = fileNode;
            this.packageName = packageName;
        }

        static FileContext from(CompilationUnit unit, String relative, Node fileNode) {
            String pkg = unit.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            FileContext context = new FileContext(relative, fileNode, pkg);
            for (ImportDeclaration imported : unit.getImports()) {
                if (imported.isStatic()) {
                    continue;
                }
                if (imported.isAsterisk()) {
                    context.asterisks.add(imported.getNameAsString());
                } else {
                    String fqcn = imported.getNameAsString();
                    context.imports.put(simpleName(fqcn), fqcn);
                }
            }
            return context;
        }

        String qualify(String simple) {
            return packageName.isEmpty() ? simple : packageName + "." + simple;
        }

        Optional<String> resolveTypeName(Type type, Map<String, TypeSymbol> types) {
            if (!(type instanceof ClassOrInterfaceType classType)) {
                return Optional.empty();
            }
            return resolveTypeName(classType, types);
        }

        Optional<String> resolveTypeName(ClassOrInterfaceType type, Map<String, TypeSymbol> types) {
            if (type.getScope().isPresent()) {
                String qualified = type.getNameWithScope();
                return Optional.of(qualified);
            }
            return resolveSimpleName(type.getNameAsString(), types);
        }

        Optional<String> resolveSimpleName(String simple, Map<String, TypeSymbol> types) {
            if (imports.containsKey(simple)) {
                return Optional.of(imports.get(simple));
            }
            if (topLevelSimpleNames.containsKey(simple)) {
                return Optional.of(topLevelSimpleNames.get(simple));
            }
            String samePackage = qualify(simple);
            if (types.containsKey(samePackage)) {
                return Optional.of(samePackage);
            }
            for (String prefix : asterisks) {
                String candidate = prefix + "." + simple;
                if (types.containsKey(candidate)) {
                    return Optional.of(candidate);
                }
            }
            if (types.containsKey(simple)) {
                return Optional.of(simple);
            }
            return Optional.of(simple.contains(".") ? simple : "java.lang." + simple);
        }

        private static String simpleName(String fqcn) {
            int dot = fqcn.lastIndexOf('.');
            return dot < 0 ? fqcn : fqcn.substring(dot + 1);
        }
    }

    private static final class TypeSymbol {
        private final String fqcn;
        private final Node node;
        private final FileContext file;
        private final boolean isInterface;
        private final TypeDeclaration<?> declaration;
        private String superFqcn;
        private final List<String> interfaces = new ArrayList<>();
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final Map<String, Type> fieldTypes = new LinkedHashMap<>();
        private final Map<String, MethodSymbol> methods = new LinkedHashMap<>();
        private final List<MethodSymbol> constructors = new ArrayList<>();

        private TypeSymbol(
                String fqcn,
                Node node,
                FileContext file,
                boolean isInterface,
                TypeDeclaration<?> declaration) {
            this.fqcn = fqcn;
            this.node = node;
            this.file = file;
            this.isInterface = isInterface;
            this.declaration = declaration;
        }
    }

    private static final class MethodSymbol {
        private final Node node;
        private final BodyDeclaration<?> declaration;

        private MethodSymbol(Node node, BodyDeclaration<?> declaration) {
            this.node = node;
            this.declaration = declaration;
        }
    }
}
