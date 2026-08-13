package io.github.patchatlas.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Frozen cohort 与选择审计的公开文件模型；不保存第三方源码或 Oracle patch 原文。 */
public final class BenchmarkArtifacts {

    public static final String DATASET_REVISION =
            "fe986fb7919be62c2a6f611ee16659e849646798";
    public static final String SEED =
            "cc279be0a2cfe38a327d24d828a49b8425ae37e7";
    public static final String SELECTOR_VERSION = "task018-v1";

    public enum Role {
        CALIBRATION,
        AGENT_BENCHMARK
    }

    public record CohortCase(
            int position,
            Role role,
            String caseId,
            String sortKey,
            String repositoryUrl,
            String issueUrl,
            String license,
            String modulePath,
            String javaVersion) {
        public CohortCase {
            if (position < 1 || position > 6) {
                throw new IllegalArgumentException("cohort position must be in 1..6");
            }
            Objects.requireNonNull(role, "role");
            requireText(caseId, "caseId");
            requireText(sortKey, "sortKey");
            requireText(repositoryUrl, "repositoryUrl");
            requireText(issueUrl, "issueUrl");
            requireText(license, "license");
            Objects.requireNonNull(modulePath, "modulePath");
            if (!javaVersion.equals("17") && !javaVersion.equals("21")) {
                throw new IllegalArgumentException("javaVersion must be 17 or 21");
            }
            Role expected = position <= 3 ? Role.CALIBRATION : Role.AGENT_BENCHMARK;
            if (role != expected) {
                throw new IllegalArgumentException("cohort role does not match position");
            }
        }
    }

    public record Cohort(
            String datasetRevision,
            String seed,
            String selectorVersion,
            String rulesSha256,
            String cohortSha256,
            List<CohortCase> cases,
            List<String> protocolLimitations) {
        public Cohort {
            requireSha(datasetRevision, "datasetRevision");
            requireSha(seed, "seed");
            requireText(selectorVersion, "selectorVersion");
            requireSha(rulesSha256, "rulesSha256");
            requireSha(cohortSha256, "cohortSha256");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            if (cases.size() != 6) {
                throw new IllegalArgumentException("frozen cohort requires exactly 6 cases");
            }
            for (int i = 0; i < cases.size(); i++) {
                if (cases.get(i).position() != i + 1) {
                    throw new IllegalArgumentException("cohort positions must be contiguous");
                }
            }
            protocolLimitations = protocolLimitations == null
                    ? List.of() : List.copyOf(protocolLimitations);
        }
    }

    public record GeneratorContextMetadata(
            String caseId,
            String issueUrl,
            String issueContentSha256,
            String buggyRevision,
            List<SourceReference> sources,
            List<ExcludedSource> excludedSources) {
        public GeneratorContextMetadata {
            requireText(caseId, "caseId");
            requireText(issueUrl, "issueUrl");
            requireSha(issueContentSha256, "issueContentSha256");
            requireSha(buggyRevision, "buggyRevision");
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            excludedSources = List.copyOf(Objects.requireNonNull(excludedSources, "excludedSources"));
        }
    }

    public record SourceReference(
            String path, String gitBlobId, String contentSha256, String selectionReason) {
        public SourceReference {
            requireText(path, "path");
            requireSha(gitBlobId, "gitBlobId");
            requireSha(contentSha256, "contentSha256");
            requireText(selectionReason, "selectionReason");
        }
    }

    public record ExcludedSource(String path, String reason) {
        public ExcludedSource {
            requireText(path, "path");
            requireText(reason, "reason");
        }
    }

    public record OracleMetadata(
            String caseId,
            String fixedRevision,
            String targetClass,
            String targetMethod,
            String knownTriggerPatchSha256) {
        public OracleMetadata {
            requireText(caseId, "caseId");
            requireSha(fixedRevision, "fixedRevision");
            requireText(targetClass, "targetClass");
            requireText(targetMethod, "targetMethod");
            requireSha(knownTriggerPatchSha256, "knownTriggerPatchSha256");
        }
    }

    /** 协议事实：模型身份与已知限制声明，从独立冻结文件 protocol.json 读取。 */
    public record ProtocolMetadata(
            String provider,
            String model,
            String endpoint,
            List<String> limitations) {
        public ProtocolMetadata {
            requireText(provider, "provider");
            requireText(model, "model");
            requireText(endpoint, "endpoint");
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
            if (limitations.isEmpty()) {
                throw new IllegalArgumentException("protocol limitations must not be empty");
            }
        }
    }

    public record StaticExclusion(String caseId, String code) {
        public StaticExclusion {
            requireText(caseId, "caseId");
            requireText(code, "code");
        }
    }

    public record ProbeAudit(
            int probePosition,
            String caseId,
            Instant startedAt,
            Instant finishedAt,
            String result,
            List<StageAudit> stages) {
        public ProbeAudit {
            if (probePosition < 1 || probePosition > FrozenCohortSelector.MAX_DYNAMIC_PROBES) {
                throw new IllegalArgumentException("probePosition out of range");
            }
            requireText(caseId, "caseId");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(finishedAt, "finishedAt");
            if (finishedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("probe finish precedes start");
            }
            requireText(result, "result");
            stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        }
    }

    public record StageAudit(String stage, String result, long durationMs) {
        public StageAudit {
            requireText(stage, "stage");
            requireText(result, "result");
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs must not be negative");
            }
        }
    }

    public record SelectionAudit(
            String datasetRevision,
            String seed,
            String selectorVersion,
            int maxDynamicProbes,
            List<StaticExclusion> staticExclusions,
            List<ProbeAudit> probes) {
        public SelectionAudit {
            requireSha(datasetRevision, "datasetRevision");
            requireSha(seed, "seed");
            requireText(selectorVersion, "selectorVersion");
            if (maxDynamicProbes != FrozenCohortSelector.MAX_DYNAMIC_PROBES) {
                throw new IllegalArgumentException("unexpected dynamic probe limit");
            }
            staticExclusions = List.copyOf(Objects.requireNonNull(staticExclusions, "staticExclusions"));
            probes = List.copyOf(Objects.requireNonNull(probes, "probes"));
        }
    }

    private final JsonMapper mapper;

    public BenchmarkArtifacts() {
        this(JsonMapper.builder().build());
    }

    BenchmarkArtifacts(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public void write(Path path, Object value) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("artifact path must have a parent");
        }
        Files.createDirectories(parent);
        mapper.writerWithDefaultPrettyPrinter().writeValue(path, value);
    }

    /** 读取 JSON 文件并映射到指定类型。 */
    public <T> T readJson(Path path, Class<T> type) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        return mapper.readValue(path.toFile(), type);
    }

    /** 读取并校验冻结的 cohort.json。 */
    public Cohort readCohort(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Cohort cohort;
        try {
            cohort = mapper.readValue(path.toFile(), Cohort.class);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("cohort file is invalid: " + ex.getMessage(), ex);
        }
        if (!cohort.datasetRevision().equals(DATASET_REVISION)) {
            throw new IllegalStateException("datasetRevision mismatch: expected " + DATASET_REVISION);
        }
        if (!cohort.seed().equals(SEED)) {
            throw new IllegalStateException("seed mismatch: expected " + SEED);
        }
        if (!cohort.selectorVersion().equals(SELECTOR_VERSION)) {
            throw new IllegalStateException("selectorVersion mismatch: expected " + SELECTOR_VERSION);
        }
        String recomputed = cohortSha256(cohort.cases());
        if (!recomputed.equals(cohort.cohortSha256())) {
            throw new IllegalStateException(
                    "cohortSha256 mismatch: expected " + recomputed
                            + " got " + cohort.cohortSha256());
        }
        for (int i = 0; i < 6; i++) {
            CohortCase c = cohort.cases().get(i);
            if (c.position() != i + 1) {
                throw new IllegalStateException("cohort position must be " + (i + 1) + " at index " + i);
            }
            Role expectedRole = i < 3 ? Role.CALIBRATION : Role.AGENT_BENCHMARK;
            if (c.role() != expectedRole) {
                throw new IllegalStateException(
                        "role mismatch at position " + c.position()
                                + ": expected " + expectedRole + " got " + c.role());
            }
        }
        return cohort;
    }

    /** 读取并校验 generator-context.json；caseId 必须与所在目录名后缀一致。 */
    public GeneratorContextMetadata readGeneratorContext(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        GeneratorContextMetadata ctx = mapper.readValue(path.toFile(), GeneratorContextMetadata.class);
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null || parent.getFileName() == null) {
            throw new IllegalStateException("generator-context path must be inside a case directory");
        }
        String directoryName = parent.getFileName().toString();
        if (!directoryName.endsWith(ctx.caseId())) {
            throw new IllegalStateException(
                    "caseId mismatch: file=" + ctx.caseId()
                            + " directory=" + directoryName);
        }
        return ctx;
    }

    /** 读取并校验 protocol.json；缺失时抛异常，不静默省略限制。 */
    public ProtocolMetadata readProtocol(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            throw new IllegalStateException("protocol file missing: " + path);
        }
        return mapper.readValue(path.toFile(), ProtocolMetadata.class);
    }

    public static String issueContentSha256(String title, String body) {
        return sha256(requireText(title, "issueTitle") + "\n" + requireText(body, "issueBody"));
    }

    public static String cohortSha256(List<CohortCase> cases) {
        Objects.requireNonNull(cases, "cases");
        StringBuilder canonical = new StringBuilder();
        for (CohortCase item : cases) {
            canonical.append(item.position()).append('\n')
                    .append(item.role()).append('\n')
                    .append(item.caseId()).append('\n')
                    .append(item.sortKey()).append('\n')
                    .append(item.repositoryUrl()).append('\n')
                    .append(item.issueUrl()).append('\n')
                    .append(item.license()).append('\n')
                    .append(item.modulePath()).append('\n')
                    .append(item.javaVersion()).append('\n');
        }
        return sha256(canonical.toString());
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requireSha(String value, String field) {
        if (value == null || !value.matches("^[0-9a-f]{40}$|^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA");
        }
    }
}
