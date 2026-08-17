package io.github.patchatlas.analysis;

import io.github.patchatlas.agent.SourceSnapshot;
import java.util.List;

/**
 * 定位工具缝：只接受仓库相对路径，类型上拿不到 Fixed Revision。
 */
public interface LocalizationTools {

    int MAX_SEARCH_HITS = 50;
    int SEARCH_CONTEXT_LINES = 3;
    int MAX_LIST_ENTRIES = 200;
    int MAX_READ_LINES = 400;
    int MAX_READ_BYTES = 64 * 1024;

    SearchHits search(String pattern, String pathGlob);

    DirectoryListing list(String path);

    FileSlice read(String path, Integer startLine, Integer span);

    SubmitDecision validateSubmit(List<String> paths);

    record SubmitDecision(List<SourceSnapshot> snapshots, String error) {
        public SubmitDecision {
            snapshots = List.copyOf(snapshots);
        }

        public boolean accepted() {
            return error == null;
        }

        public static SubmitDecision accept(List<SourceSnapshot> snapshots) {
            return new SubmitDecision(snapshots, null);
        }

        public static SubmitDecision reject(String error) {
            return new SubmitDecision(List.of(), java.util.Objects.requireNonNull(error, "error"));
        }
    }

    record SearchHits(List<Hit> hits, boolean truncated) {
        public SearchHits {
            hits = List.copyOf(hits);
        }

        public record Hit(String path, int line, List<String> snippet) {
            public Hit {
                snippet = List.copyOf(snippet);
            }
        }
    }

    record DirectoryListing(List<String> names, boolean truncated) {
        public DirectoryListing {
            names = List.copyOf(names);
        }
    }

    record FileSlice(String path, int startLine, List<String> lines, boolean truncated) {
        public FileSlice {
            lines = List.copyOf(lines);
        }
    }
}
