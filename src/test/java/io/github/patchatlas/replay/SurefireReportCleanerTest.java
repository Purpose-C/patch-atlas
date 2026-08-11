package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireReportCleanerTest {

    private final SurefireReportCleaner cleaner = new SurefireReportCleaner();

    @TempDir
    Path tempDir;

    @Test
    void succeedsWhenReportsDirectoryMissing() {
        assertThat(cleaner.clean(tempDir, "")).isInstanceOf(SurefireReportCleanup.Succeeded.class);
    }

    @Test
    void deletesRegularReportFilesOnly() throws Exception {
        Path reports = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(reports);
        Path xml = reports.resolve("TEST-A.xml");
        Files.writeString(xml, "<testsuite/>", StandardCharsets.UTF_8);

        assertThat(cleaner.clean(tempDir, "")).isInstanceOf(SurefireReportCleanup.Succeeded.class);
        assertThat(Files.exists(xml)).isFalse();
        assertThat(Files.isDirectory(reports)).isTrue();
    }

    @Test
    void rejectsSymlinkedReportsDirectory() throws Exception {
        Path real = tempDir.resolve("real-reports");
        Files.createDirectory(real);
        Path target = tempDir.resolve("target");
        Files.createDirectory(target);
        Files.createSymbolicLink(target.resolve("surefire-reports"), real);

        SurefireReportCleanup result = cleaner.clean(tempDir, "");
        assertThat(result).isInstanceOf(SurefireReportCleanup.Failed.class);
    }

    @Test
    void rejectsSymlinkedEntryInsideReports() throws Exception {
        Path reports = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(reports);
        Path outside = tempDir.resolve("outside.xml");
        Files.writeString(outside, "x", StandardCharsets.UTF_8);
        Files.createSymbolicLink(reports.resolve("TEST-Evil.xml"), outside);

        SurefireReportCleanup result = cleaner.clean(tempDir, "");
        assertThat(result).isInstanceOf(SurefireReportCleanup.Failed.class);
        assertThat(Files.exists(outside)).isTrue();
    }

    @Test
    void modulePathReportsStayUnderWorkspace() throws Exception {
        Path reports = tempDir.resolve("mod/target/surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-M.xml"), "<testsuite/>", StandardCharsets.UTF_8);

        assertThat(cleaner.clean(tempDir, "mod")).isInstanceOf(SurefireReportCleanup.Succeeded.class);
        assertThat(Files.exists(reports.resolve("TEST-M.xml"))).isFalse();
    }

    @Test
    void rejectsSymlinkedModuleAncestorBeforeDeletingOutsideWorkspace() throws Exception {
        Path outside = tempDir.resolve("outside-workspace");
        Files.createDirectories(outside.resolve("target/surefire-reports"));
        Files.writeString(
                outside.resolve("target/surefire-reports/TEST-Outside.xml"),
                "<testsuite/>",
                StandardCharsets.UTF_8);

        Path workspace = tempDir.resolve("workspace");
        Files.createDirectory(workspace);
        // 恶意仓库：module 目录是指向工作区外的符号链接
        Files.createSymbolicLink(workspace.resolve("evil-mod"), outside);

        SurefireReportCleanup result = cleaner.clean(workspace, "evil-mod");
        assertThat(result).isInstanceOf(SurefireReportCleanup.Failed.class);
        // 外部文件不得被删除
        assertThat(Files.exists(outside.resolve("target/surefire-reports/TEST-Outside.xml"))).isTrue();
    }

    @Test
    void rejectsSymlinkedTargetDirectory() throws Exception {
        Path outside = tempDir.resolve("outside-target");
        Files.createDirectories(outside.resolve("surefire-reports"));
        Path workspace = tempDir.resolve("ws");
        Files.createDirectory(workspace);
        Files.createSymbolicLink(workspace.resolve("target"), outside);

        assertThat(cleaner.clean(workspace, "")).isInstanceOf(SurefireReportCleanup.Failed.class);
    }
}
