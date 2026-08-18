package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.analysis.CodeGraph.Node;
import io.github.patchatlas.analysis.CodeGraph.NodeKind;
import io.github.patchatlas.analysis.CodeGraph.SourceLocation;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void cacheHitDoesNotRebuild() {
        CountingBuilder inner = new CountingBuilder(sample());
        CachingCodeGraphBuilder builder = caching(inner, graphCache(), PARSER, 1);

        builder.build(temp.resolve("ws"), REVISION);
        builder.build(temp.resolve("ws"), REVISION);

        assertThat(inner.calls.get()).isEqualTo(1);
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
