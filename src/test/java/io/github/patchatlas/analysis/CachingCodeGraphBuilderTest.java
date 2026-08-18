package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.analysis.CodeGraph.Edge;
import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.Node;
import io.github.patchatlas.analysis.CodeGraph.NodeKind;
import io.github.patchatlas.analysis.CodeGraph.SourceLocation;
import io.github.patchatlas.analysis.CodeGraph.UnresolvedKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 图缓存按四元组键命中；键不完全匹配则整图重建。 */
class CachingCodeGraphBuilderTest {

    private static final String REPO = "https://example.invalid/shop.git";
    private static final String REVISION = "rev-f";
    private static final String PARSER = "javaparser-3.26.4";

    @TempDir
    Path temp;

    @Test
    void sameRepoAndRevisionYieldByteIdenticalGraphFiles() throws Exception {
        Path cache = graphCache();
        CountingBuilder inner = new CountingBuilder(sample());
        CachingCodeGraphBuilder builder = caching(inner, cache, PARSER, 1);

        builder.build(temp.resolve("ws"), REVISION);
        byte[] first = Files.readAllBytes(graphFile(cache));
        CodeGraph second = builder.build(temp.resolve("ws"), REVISION);
        byte[] after = Files.readAllBytes(graphFile(cache));

        assertThat(after).isEqualTo(first);
        assertThat(second.revision()).isEqualTo(REVISION);
        assertThat(second.nodes()).hasSize(1);
    }

    @Test
    void cachedGraphRoundTripsEdgesIncludingUnresolved() {
        Node caller = new Node(
                "method:A#run",
                NodeKind.METHOD,
                "A#run",
                new SourceLocation("A.java", 4));
        Node callee = new Node(
                "method:B#ping",
                NodeKind.METHOD,
                "B#ping",
                new SourceLocation("B.java", 3));
        Edge confirmed = new Edge(
                EdgeKind.CALLS,
                ImpactConfidence.CONFIRMED,
                caller,
                callee,
                new SourceLocation("A.java", 5),
                null,
                List.of());
        Edge hole = new Edge(
                EdgeKind.CALLS,
                ImpactConfidence.POSSIBLE,
                caller,
                null,
                new SourceLocation("A.java", 6),
                UnresolvedKind.REFLECTION,
                List.of(callee));
        CodeGraph graph = new CodeGraph(REVISION, List.of(caller, callee), List.of(confirmed, hole));
        CachingCodeGraphBuilder builder =
                caching(new CountingBuilder(graph), graphCache(), PARSER, 1);

        builder.build(temp.resolve("ws"), REVISION);
        CodeGraph loaded = builder.build(temp.resolve("ws"), REVISION);

        assertThat(loaded.edges()).hasSize(2);
        Edge loadedConfirmed = loaded.edges().getFirst();
        assertThat(loadedConfirmed.target()).isNotNull();
        assertThat(loadedConfirmed.target().name()).isEqualTo("B#ping");
        assertThat(loadedConfirmed.unresolvedKind()).isNull();
        Edge loadedHole = loaded.edges().get(1);
        assertThat(loadedHole.target()).isNull();
        assertThat(loadedHole.unresolvedKind()).isEqualTo(UnresolvedKind.REFLECTION);
        assertThat(loadedHole.candidates()).extracting(Node::name).containsExactly("B#ping");
    }

    @Test
    void defaultParserAndSchemaVersionsAreUsedWhenOmitted() {
        CountingBuilder inner = new CountingBuilder(sample());
        new CachingCodeGraphBuilder(inner, graphCache(), REPO).build(temp.resolve("ws"), REVISION);
        assertThat(inner.calls.get()).isEqualTo(1);
    }

    @Test
    void sourceLocationRejectsNonPositiveLine() {
        assertThatThrownBy(() -> new SourceLocation("A.java", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        Edge edge = new Edge(
                EdgeKind.DECLARES,
                ImpactConfidence.CONFIRMED,
                sample().nodes().getFirst(),
                null,
                new SourceLocation("A.java", 1),
                null,
                null);
        assertThat(edge.candidates()).isEmpty();
        assertThat(edge.target()).isNull();
    }

    @Test
    void cacheHitDoesNotRebuild() {
        CountingBuilder inner = new CountingBuilder(sample());
        CachingCodeGraphBuilder builder = caching(inner, graphCache(), PARSER, 1);

        builder.build(temp.resolve("ws"), REVISION);
        builder.build(temp.resolve("ws"), REVISION);

        assertThat(inner.calls.get()).isEqualTo(1);
    }

    @Test
    void collidingConcatenationsProduceDistinctCacheKeys() throws Exception {
        Path cache = graphCache();
        CountingBuilder firstInner = new CountingBuilder(sample());
        CountingBuilder secondInner = new CountingBuilder(sample());
        new CachingCodeGraphBuilder(firstInner, cache, "a", "p", 1)
                .build(temp.resolve("ws"), "bc");
        new CachingCodeGraphBuilder(secondInner, cache, "ab", "p", 1)
                .build(temp.resolve("ws"), "c");

        try (Stream<Path> entries = Files.list(cache)) {
            List<Path> dirs = entries.filter(Files::isDirectory).toList();
            assertThat(dirs).hasSize(2);
        }
        assertThat(firstInner.calls.get()).isEqualTo(1);
        assertThat(secondInner.calls.get()).isEqualTo(1);
    }

    @Test
    void delimiterChangeRebuildsWithoutBumpingSchemaVersion() throws Exception {
        Path cache = graphCache();
        Path stale = cache.resolve(undelimitedKey(REPO, REVISION, PARSER, 1));
        Files.createDirectories(stale);
        Files.writeString(
                stale.resolve("graph.json"),
                "{\"revision\":\"stale\",\"nodes\":[],\"edges\":[]}",
                StandardCharsets.UTF_8);
        CountingBuilder inner = new CountingBuilder(sample());

        CodeGraph built = caching(inner, cache, PARSER, 1).build(temp.resolve("ws"), REVISION);

        assertThat(built.revision()).isEqualTo(REVISION);
        assertThat(inner.calls.get()).isEqualTo(1);
        assertThat(CodeGraph.SCHEMA_VERSION).isEqualTo(1);
        try (Stream<Path> entries = Files.list(cache)) {
            assertThat(entries.filter(Files::isDirectory).map(path -> path.getFileName().toString()))
                    .contains(undelimitedKey(REPO, REVISION, PARSER, 1))
                    .hasSize(2);
        }
    }

    @Test
    void schemaVersionBumpDoesNotReuseCache() {
        Path cache = graphCache();
        CountingBuilder inner = new CountingBuilder(sample());
        caching(inner, cache, PARSER, 1).build(temp.resolve("ws"), REVISION);

        caching(inner, cache, PARSER, 2).build(temp.resolve("ws"), REVISION);

        assertThat(inner.calls.get()).isEqualTo(2);
    }

    @Test
    void parserVersionChangeDoesNotReuseCache() {
        Path cache = graphCache();
        CountingBuilder inner = new CountingBuilder(sample());
        caching(inner, cache, PARSER, 1).build(temp.resolve("ws"), REVISION);

        caching(inner, cache, "javaparser-9.9.9", 1).build(temp.resolve("ws"), REVISION);

        assertThat(inner.calls.get()).isEqualTo(2);
    }

    @Test
    void concurrentBuildsLeaveOneCompleteDirectory() throws Exception {
        Path cache = graphCache();
        CountingBuilder inner = new CountingBuilder(sample());
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger failures = new AtomicInteger();
        CachingCodeGraphBuilder builder = caching(new CodeGraphBuilder() {
            @Override
            public CodeGraph build(Path workspace, String revision) {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                return inner.build(workspace, revision);
            }
        }, cache, PARSER, 1);

        Runnable task = () -> {
            try {
                CodeGraph graph = builder.build(temp.resolve("ws"), REVISION);
                assertThat(graph.nodes()).hasSize(1);
            } catch (RuntimeException ex) {
                failures.incrementAndGet();
                throw ex;
            } finally {
                done.countDown();
            }
        };
        Thread first = new Thread(task, "graph-cache-a");
        Thread second = new Thread(task, "graph-cache-b");
        first.start();
        second.start();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures.get()).isZero();

        try (Stream<Path> entries = Files.list(cache)) {
            List<Path> dirs = entries.toList();
            assertThat(dirs).hasSize(1);
            assertThat(dirs.getFirst().getFileName().toString()).matches("[0-9a-f]{64}");
            assertThat(Files.isRegularFile(dirs.getFirst().resolve("graph.json"))).isTrue();
        }
    }

    private CachingCodeGraphBuilder caching(
            CodeGraphBuilder inner, Path cache, String parserVersion, int schemaVersion) {
        return new CachingCodeGraphBuilder(inner, cache, REPO, parserVersion, schemaVersion);
    }

    private Path graphCache() {
        return temp.resolve(".patch-atlas-cache").resolve("graph");
    }

    private static Path graphFile(Path cache) throws Exception {
        try (Stream<Path> entries = Files.list(cache)) {
            Path dir = entries.filter(Files::isDirectory).findFirst().orElseThrow();
            return dir.resolve("graph.json");
        }
    }

    private static CodeGraph sample() {
        Node file = new Node(
                "file:A.java",
                NodeKind.FILE,
                "A.java",
                new SourceLocation("A.java", 1));
        return new CodeGraph(REVISION, List.of(file), List.of());
    }

    private static String undelimitedKey(
            String repositoryUrl, String revision, String parserVersion, int schemaVersion) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((repositoryUrl + revision + parserVersion + schemaVersion)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class CountingBuilder implements CodeGraphBuilder {
        private final AtomicInteger calls = new AtomicInteger();
        private final CodeGraph graph;

        private CountingBuilder(CodeGraph graph) {
            this.graph = graph;
        }

        @Override
        public CodeGraph build(Path workspace, String revision) {
            calls.incrementAndGet();
            return graph;
        }
    }
}
