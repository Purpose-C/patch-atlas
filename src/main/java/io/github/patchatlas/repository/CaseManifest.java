package io.github.patchatlas.repository;

/**
 * 案例清单:在类型层面把 Generator Context 与 Oracle Data 拆开(AD-006)。
 *
 * <p>拿到 {@link #generatorContext()} 的调用方在编译期无法访问 Fixed Revision
 * 或已知触发测试。
 */
public record CaseManifest(GeneratorContext generatorContext, OracleData oracleData) {

    public CaseManifest {
        if (generatorContext == null) {
            throw new IllegalArgumentException("generatorContext is required");
        }
        if (oracleData == null) {
            throw new IllegalArgumentException("oracleData is required");
        }
    }

    /** 生成器 / 定位器可见的字段。绝不包含 Fixed Revision 或已知触发测试。 */
    public record GeneratorContext(
            String caseId,
            String repositoryUrl,
            String license,
            String issueUrl,
            String buggyRevision,
            String modulePath,
            String javaVersion) {

        public GeneratorContext {
            requireNonBlank(caseId, "caseId");
            requireNonBlank(repositoryUrl, "repositoryUrl");
            requireNonBlank(buggyRevision, "buggyRevision");
        }
    }

    /** 仅供验证器裁决使用的答案数据。不得进入任何生成或定位提示。 */
    public record OracleData(String fixedRevision, String knownTriggerTest) {

        public OracleData {
            requireNonBlank(fixedRevision, "fixedRevision");
            requireNonBlank(knownTriggerTest, "knownTriggerTest");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
