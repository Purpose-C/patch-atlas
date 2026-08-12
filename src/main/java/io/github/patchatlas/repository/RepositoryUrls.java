package io.github.patchatlas.repository;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 仓库 URL 校验：仅匿名公开 GitHub HTTPS，拒绝 user-info/凭据。
 */
public final class RepositoryUrls {

    private RepositoryUrls() {}

    public static void requireAnonymousGithubHttps(String repositoryUrl) {
        if (!isAnonymousGithubHttps(repositoryUrl)) {
            throw new IllegalArgumentException(
                    "repositoryUrl must be an anonymous public GitHub HTTPS repository URL");
        }
    }

    public static boolean isAnonymousGithubHttps(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(repositoryUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return false;
            }
            String path = uri.getPath();
            if (path == null
                    || path.isEmpty()
                    || path.endsWith("/")
                    || !path.equals(uri.getRawPath())) {
                return false;
            }
            String[] segments = path.substring(1).split("/", -1);
            if (segments.length != 2) {
                return false;
            }
            String repositoryName = segments[1].endsWith(".git")
                    ? segments[1].substring(0, segments[1].length() - 4)
                    : segments[1];
            return isSafeGithubSegment(segments[0]) && isSafeGithubSegment(repositoryName);
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    /** 可选 issue URL：若提供，禁止 user-info 凭据。 */
    public static void requireNoCredentialUserInfo(String url, String fieldName) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            URI uri = new URI(url);
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException(fieldName + " must not contain credentials");
            }
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(fieldName + " is not a valid URL", ex);
        }
    }

    private static boolean isSafeGithubSegment(String segment) {
        return !segment.equals(".")
                && !segment.equals("..")
                && segment.matches("[A-Za-z0-9_.-]+");
    }
}
