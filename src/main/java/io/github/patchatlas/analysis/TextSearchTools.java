package io.github.patchatlas.analysis;

import java.nio.file.Path;
import java.util.List;

/** 文本定位门面：发现走 {@link TextDiscoveryTools}，读写走 {@link WorkspaceFileTools}。 */
public final class TextSearchTools implements LocalizationTools {

    private final WorkspaceFileTools files;
    private final TextDiscoveryTools discovery;

    public TextSearchTools(Path workspace) {
        TrustedWorkspace trusted = new TrustedWorkspace(workspace);
        this.files = new WorkspaceFileTools(trusted);
        this.discovery = new TextDiscoveryTools(trusted);
    }

    public WorkspaceTools workspaceTools() {
        return files;
    }

    public TextDiscoveryTools discoveryTools() {
        return discovery;
    }

    @Override
    public SearchHits search(String pattern, String pathGlob) {
        return discovery.search(pattern, pathGlob);
    }

    @Override
    public DirectoryListing list(String path) {
        return discovery.list(path);
    }

    @Override
    public FileSlice read(String path, Integer startLine, Integer span) {
        return files.read(path, startLine, span);
    }

    @Override
    public SubmitDecision validateSubmit(List<String> paths) {
        return files.validateSubmit(paths);
    }

    Path workspace() {
        return files.trustedWorkspace().path();
    }
}
