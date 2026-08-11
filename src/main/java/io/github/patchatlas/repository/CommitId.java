package io.github.patchatlas.repository;

import java.util.Locale;
import java.util.Objects;

/**
 * 仓库模块对外暴露的提交指纹。
 *
 * <p>刻意不用 JGit 的 {@code ObjectId}:调用方(sandbox / replay)只需要一个不可变的
 * SHA 字符串,不应被迫依赖 JGit。
 */
public record CommitId(String sha) {

    public CommitId {
        Objects.requireNonNull(sha, "sha");
        if (!isFullSha(sha)) {
            throw new IllegalArgumentException("sha must be a full 40-character hexadecimal object id");
        }
        sha = sha.toLowerCase(Locale.ROOT);
    }

    static boolean isFullSha(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40}");
    }

    @Override
    public String toString() {
        return sha;
    }
}
