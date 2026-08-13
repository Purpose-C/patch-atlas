package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.GenerationRequest;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.AttemptPhase;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.RunOutcome;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.SingleAttemptEvidence;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL Verification Run 持久化深模块：按领域动作命名，无通用 save。
 *
 * <p>领取使用短事务 + {@code FOR UPDATE SKIP LOCKED}；不在模型/Git/Docker 调用期间持有事务。
 */
public final class PostgresRunStore {

    public record ReservedGenerationAttempt(ClaimedRun claim, int ordinal) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SourceSnapshotsCodec snapshotsCodec;
    private final AttemptRecordCodec attemptCodec;
    private final RunStateMachine stateMachine;

    public PostgresRunStore(DataSource dataSource) {
        this(
                JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new SourceSnapshotsCodec(),
                new AttemptRecordCodec(),
                new RunStateMachine());
    }

    PostgresRunStore(
            JdbcClient jdbc,
            TransactionTemplate tx,
            SourceSnapshotsCodec snapshotsCodec,
            AttemptRecordCodec attemptCodec,
            RunStateMachine stateMachine) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.snapshotsCodec = Objects.requireNonNull(snapshotsCodec, "snapshotsCodec");
        this.attemptCodec = Objects.requireNonNull(attemptCodec, "attemptCodec");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
    }

    public UUID submit(RunSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        UUID id = UUID.randomUUID();
        String snapshotsJson = snapshotsCodec.encode(submission.sourceSnapshots());
        jdbc.sql(
                        """
                        INSERT INTO verification_run (
                          id, mode, case_id, repository_url, license, issue_url,
                          issue_title, issue_body, buggy_revision, fixed_revision,
                          module_path, java_version, network_mode, source_snapshots, input_schema_version,
                          state, version, recovery_count, replay_round
                        ) VALUES (
                          :id, :mode, :caseId, :repositoryUrl, :license, :issueUrl,
                          :issueTitle, :issueBody, :buggyRevision, :fixedRevision,
                          :modulePath, :javaVersion, :networkMode, CAST(:sourceSnapshots AS jsonb), :schemaVersion,
                          :state, 0, 0, 0
                        )
                        """)
                .param("id", id)
                .param("mode", submission.mode().name())
                .param("caseId", submission.caseId())
                .param("repositoryUrl", submission.repositoryUrl())
                .param("license", submission.license())
                .param("issueUrl", submission.issueUrl())
                .param("issueTitle", submission.issueTitle())
                .param("issueBody", submission.issueBody())
                .param("buggyRevision", submission.buggyRevision())
                .param("fixedRevision", submission.fixedRevision())
                .param("modulePath", submission.modulePath())
                .param("javaVersion", submission.javaVersion())
                .param("networkMode", submission.networkMode().name())
                .param("sourceSnapshots", snapshotsJson)
                .param("schemaVersion", SourceSnapshotsCodec.SCHEMA_VERSION)
                .param("state", RunState.QUEUED.name())
                .update();
        return id;
    }

    /**
     * 公开 API 幂等提交：原子插入或返回已有 Run / 指纹冲突。
     *
     * <p>不得先查再插；内部 fixture 可继续使用 {@link #submit(RunSubmission)}。
     */
    public IdempotentSubmitResult submitIdempotent(
            IdempotencyKey key, String submissionSha256, RunSubmission submission) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(submission, "submission");
        if (submissionSha256 == null || !submissionSha256.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("submissionSha256 must be 64 lowercase hex chars");
        }
        return tx.execute(status -> {
            UUID id = UUID.randomUUID();
            String snapshotsJson = snapshotsCodec.encode(submission.sourceSnapshots());
            // ON CONFLICT DO NOTHING：避免唯一冲突中止事务（PostgreSQL 25P02）
            Optional<UUID> insertedId = jdbc.sql(
                            """
                            INSERT INTO verification_run (
                              id, mode, case_id, repository_url, license, issue_url,
                              issue_title, issue_body, buggy_revision, fixed_revision,
                              module_path, java_version, network_mode, source_snapshots,
                              input_schema_version, state, version, recovery_count, replay_round,
                              idempotency_key, submission_sha256
                            ) VALUES (
                              :id, :mode, :caseId, :repositoryUrl, :license, :issueUrl,
                              :issueTitle, :issueBody, :buggyRevision, :fixedRevision,
                              :modulePath, :javaVersion, :networkMode, CAST(:sourceSnapshots AS jsonb),
                              :schemaVersion, :state, 0, 0, 0, :idempotencyKey, :submissionSha256
                            )
                            ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL
                            DO NOTHING
                            RETURNING id
                            """)
                    .param("id", id)
                    .param("mode", submission.mode().name())
                    .param("caseId", submission.caseId())
                    .param("repositoryUrl", submission.repositoryUrl())
                    .param("license", submission.license())
                    .param("issueUrl", submission.issueUrl())
                    .param("issueTitle", submission.issueTitle())
                    .param("issueBody", submission.issueBody())
                    .param("buggyRevision", submission.buggyRevision())
                    .param("fixedRevision", submission.fixedRevision())
                    .param("modulePath", submission.modulePath())
                    .param("javaVersion", submission.javaVersion())
                    .param("networkMode", submission.networkMode().name())
                    .param("sourceSnapshots", snapshotsJson)
                    .param("schemaVersion", SourceSnapshotsCodec.SCHEMA_VERSION)
                    .param("state", RunState.QUEUED.name())
                    .param("idempotencyKey", key.value())
                    .param("submissionSha256", submissionSha256)
                    .query((rs, rowNum) -> (UUID) rs.getObject("id"))
                    .optional();
            if (insertedId.isPresent()) {
                return new IdempotentSubmitResult.Accepted(insertedId.orElseThrow(), RunState.QUEUED, true);
            }
            return loadIdempotentResult(key, submissionSha256);
        });
    }

    public RunListPage listRuns(int limit, Optional<RunListCursor> cursor) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Objects.requireNonNull(cursor, "cursor");
        int fetch = limit + 1;
        List<RunSummary> rows;
        if (cursor.isEmpty()) {
            rows = jdbc.sql(
                            """
                            SELECT id, mode, state, issue_title, repository_url,
                                   verdict, failure_category,
                                   created_at, updated_at, completed_at
                              FROM verification_run
                             ORDER BY created_at DESC, id DESC
                             LIMIT :limit
                            """)
                    .param("limit", fetch)
                    .query((rs, rowNum) -> mapSummary(rs))
                    .list();
        } else {
            RunListCursor c = cursor.orElseThrow();
            rows = jdbc.sql(
                            """
                            SELECT id, mode, state, issue_title, repository_url,
                                   verdict, failure_category,
                                   created_at, updated_at, completed_at
                              FROM verification_run
                             WHERE (created_at, id) < (:createdAt, :runId)
                             ORDER BY created_at DESC, id DESC
                             LIMIT :limit
                            """)
                    .param("createdAt", Timestamp.from(c.createdAt()))
                    .param("runId", c.runId())
                    .param("limit", fetch)
                    .query((rs, rowNum) -> mapSummary(rs))
                    .list();
        }
        Optional<String> next = Optional.empty();
        if (rows.size() > limit) {
            RunSummary last = rows.get(limit - 1);
            next = Optional.of(new RunListCursor(last.createdAt(), last.runId()).encode());
            rows = rows.subList(0, limit);
        }
        return new RunListPage(rows, next);
    }

    public Optional<RunDetailView> findRunDetail(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        Optional<RunDetailView> header = jdbc.sql(
                        """
                        SELECT r.id, r.mode, r.state, r.case_id,
                               r.repository_url, r.issue_url, r.issue_title, r.issue_body,
                               r.buggy_revision, r.fixed_revision, r.module_path,
                               r.java_version, r.network_mode,
                               r.generation_attempt_count, r.model_provider, r.model_name,
                               r.model_input_tokens, r.model_output_tokens, r.model_total_tokens,
                               r.model_usage_record_count,
                               r.verdict, r.failure_stage, r.failure_category, r.failure_summary,
                               r.created_at, r.updated_at, r.completed_at, r.final_replay_round,
                               c.patch_text, c.patch_sha256, c.target_class, c.target_method
                          FROM verification_run r
                          LEFT JOIN candidate_test_patch c ON c.run_id = r.id
                         WHERE r.id = :id
                        """)
                .param("id", runId)
                .query((rs, rowNum) -> mapDetailHeader(rs))
                .optional();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        RunDetailView base = header.orElseThrow();
        List<RunAttemptView> attempts = loadAttemptViews(runId, base.candidate().map(c -> c.targetTest()));
        return Optional.of(new RunDetailView(
                base.runId(),
                base.mode(),
                base.state(),
                base.caseId(),
                base.createdAt(),
                base.updatedAt(),
                base.completedAt(),
                base.input(),
                base.executionPolicy(),
                base.generation(),
                base.candidate(),
                base.verdict(),
                base.failure(),
                attempts));
    }

    public Optional<RunDetails> findRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbc.sql(
                        """
                        SELECT r.id, r.mode, r.state, r.version, r.case_id, r.repository_url,
                               r.issue_title, r.buggy_revision, r.fixed_revision,
                               r.verdict, r.failure_stage, r.failure_category, r.failure_summary,
                               r.created_at, r.updated_at, r.completed_at,
                               c.patch_text, c.patch_sha256, c.target_class, c.target_method
                          FROM verification_run r
                          LEFT JOIN candidate_test_patch c ON c.run_id = r.id
                         WHERE r.id = :id
                        """)
                .param("id", runId)
                .query((rs, rowNum) -> mapDetails(rs))
                .optional();
    }

    /**
     * 生成器白名单投影：SQL 与返回类型均不含 fixed_revision。
     */
    public GenerationInput loadGenerationInput(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbc.sql(
                        """
                        SELECT case_id, repository_url, license, issue_url,
                               buggy_revision, module_path, java_version,
                               issue_title, issue_body, source_snapshots, input_schema_version
                          FROM verification_run
                         WHERE id = :id
                        """)
                .param("id", runId)
                .query((rs, rowNum) -> {
                    int schemaVersion = rs.getShort("input_schema_version");
                    String snapshotsJson = rs.getString("source_snapshots");
                    List<SourceSnapshot> snapshots =
                            snapshotsCodec.decode(snapshotsJson, schemaVersion);
                    return GenerationInputMapper.fromPersistedColumns(
                            rs.getString("case_id"),
                            rs.getString("repository_url"),
                            rs.getString("license"),
                            rs.getString("issue_url"),
                            rs.getString("buggy_revision"),
                            rs.getString("module_path"),
                            rs.getString("java_version"),
                            rs.getString("issue_title"),
                            rs.getString("issue_body"),
                            snapshots);
                })
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
    }

    /** 生成与 Replay 共用的执行策略投影；不读取 Fixed revision 或其他 Oracle 数据。 */
    public MavenExecutionPolicy loadExecutionPolicy(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbc.sql(
                        """
                        SELECT java_version, network_mode
                          FROM verification_run
                         WHERE id = :id
                        """)
                .param("id", runId)
                .query((rs, rowNum) -> new MavenExecutionPolicy(
                        rs.getString("java_version"),
                        MavenNetworkMode.valueOf(rs.getString("network_mode"))))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
    }

    /**
     * Replay/workspace 投影：可含 Fixed revision；仅 run 包使用，不得交给 TestGenerator。
     */
    public ReplayWorkspaceProjection loadReplayWorkspaceProjection(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbc.sql(
                        """
                        SELECT mode, repository_url, buggy_revision, fixed_revision, module_path,
                               java_version, network_mode
                          FROM verification_run
                         WHERE id = :id
                        """)
                .param("id", runId)
                .query((rs, rowNum) -> {
                    VerificationMode mode = VerificationMode.valueOf(rs.getString("mode"));
                    String repositoryUrl = rs.getString("repository_url");
                    String buggy = rs.getString("buggy_revision");
                    String modulePath = rs.getString("module_path");
                    if (modulePath == null) {
                        modulePath = "";
                    }
                    MavenExecutionPolicy executionPolicy = new MavenExecutionPolicy(
                            rs.getString("java_version"),
                            MavenNetworkMode.valueOf(rs.getString("network_mode")));
                    return switch (mode) {
                        case LIVE -> new ReplayWorkspaceProjection.Live(
                                repositoryUrl, buggy, modulePath, executionPolicy);
                        case HISTORICAL -> new ReplayWorkspaceProjection.Historical(
                                repositoryUrl,
                                buggy,
                                rs.getString("fixed_revision"),
                                modulePath,
                                executionPolicy);
                    };
                })
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
    }

    public Optional<ClaimedRun> claimNext(String owner, Duration leaseDuration) {
        return claimNext(owner, leaseDuration, ignored -> {});
    }

    public Optional<ClaimedRun> claimNext(
            String owner, Duration leaseDuration, java.util.function.Consumer<RunDetails> onRecoveryExhausted) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(onRecoveryExhausted, "onRecoveryExhausted");
        if (owner.isBlank() || owner.length() > RunLease.MAX_OWNER_CHARS) {
            throw new IllegalArgumentException("invalid lease owner");
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        long leaseSeconds = Math.max(1L, leaseDuration.toSeconds());

        java.util.List<RunDetails> exhausted = new java.util.ArrayList<>();
        Optional<ClaimedRun> result = tx.execute(
                status -> claimNextInTransaction(owner, leaseSeconds, exhausted));
        exhausted.forEach(onRecoveryExhausted);
        return result;
    }

    /**
     * 续租：必须匹配 lease token + version + state；失败抛 {@link StaleClaimException}。
     */
    public ClaimedRun renewLease(ClaimHandle handle, String owner, Duration leaseDuration) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (owner.isBlank() || owner.length() > RunLease.MAX_OWNER_CHARS) {
            throw new IllegalArgumentException("invalid lease owner");
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        long leaseSeconds = Math.max(1L, leaseDuration.toSeconds());
        long newVersion = RunLeaseRules.nextVersion(handle.version());

        return tx.execute(status -> {
            int updated = jdbc.sql(
                            """
                            UPDATE verification_run
                               SET lease_expires_at = CURRENT_TIMESTAMP + make_interval(secs => :secs),
                                   version = :version,
                                   updated_at = CURRENT_TIMESTAMP
                             WHERE id = :id
                               AND state = :state
                               AND lease_token = :token
                               AND lease_owner = :owner
                               AND version = :expectedVersion
                            """)
                    .param("secs", leaseSeconds)
                    .param("version", newVersion)
                    .param("id", handle.runId())
                    .param("state", handle.state().name())
                    .param("token", handle.leaseToken())
                    .param("owner", owner)
                    .param("expectedVersion", handle.version())
                    .update();
            if (updated != 1) {
                throw new StaleClaimException(handle.runId(), "stale renew on run " + handle.runId());
            }
            Instant expiresAt = loadLeaseExpiry(handle.runId());
            Optional<PersistedCandidatePatch> candidate = Optional.empty();
            if (handle.state() == RunState.REPLAYING) {
                candidate = Optional.of(loadCandidateRequired(handle.runId()));
            }
            ClaimRow row = loadClaimRow(handle.runId());
            return new ClaimedRun(
                    handle.runId(),
                    row.mode,
                    handle.state(),
                    newVersion,
                    new RunLease(handle.leaseToken(), owner, expiresAt),
                    row.recoveryCount,
                    row.replayRound,
                    candidate);
        });
    }

    /**
     * 预占全局 Generation Attempt：count N→N+1，返回 ordinal。
     *
     * <p>第四次预占抛 {@link GenerationAttemptsExhaustedException}。
     */
    public ReservedGenerationAttempt reserveGenerationAttempt(
            ClaimHandle handle, String provider, String modelName) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelName, "modelName");
        if (handle.state() != RunState.GENERATING) {
            throw new IllegalArgumentException("reserve requires GENERATING");
        }
        long newVersion = RunLeaseRules.nextVersion(handle.version());
        return tx.execute(status -> {
            GenerationReservationRow current = jdbc.sql(
                            """
                            SELECT mode, generation_attempt_count, recovery_count, replay_round,
                                   lease_owner, lease_expires_at
                              FROM verification_run
                             WHERE id = :id
                               AND state = 'GENERATING'
                               AND lease_token = :token
                               AND version = :expectedVersion
                            """)
                    .param("id", handle.runId())
                    .param("token", handle.leaseToken())
                    .param("expectedVersion", handle.version())
                    .query((rs, rowNum) -> new GenerationReservationRow(
                            VerificationMode.valueOf(rs.getString("mode")),
                            rs.getInt("generation_attempt_count"),
                            rs.getInt("recovery_count"),
                            rs.getInt("replay_round"),
                            rs.getString("lease_owner"),
                            rs.getTimestamp("lease_expires_at").toInstant()))
                    .optional()
                    .orElse(null);
            if (current == null) {
                throw new StaleClaimException(handle.runId(), "stale reserve on " + handle.runId());
            }
            if (current.attemptCount() >= GenerationRequest.MAX_ATTEMPTS) {
                throw new GenerationAttemptsExhaustedException(handle.runId());
            }
            int next = current.attemptCount() + 1;
            int updated = jdbc.sql(
                            """
                            UPDATE verification_run
                               SET generation_attempt_count = :next,
                                   model_provider = COALESCE(model_provider, :provider),
                                   model_name = COALESCE(model_name, :modelName),
                                   version = :version,
                                   updated_at = CURRENT_TIMESTAMP
                             WHERE id = :id
                               AND state = 'GENERATING'
                               AND lease_token = :token
                               AND version = :expectedVersion
                               AND generation_attempt_count = :current
                               AND (
                                     (generation_attempt_count = 0)
                                  OR (model_provider = :provider AND model_name = :modelName)
                               )
                            """)
                    .param("next", next)
                    .param("provider", provider)
                    .param("modelName", modelName)
                    .param("version", newVersion)
                    .param("id", handle.runId())
                    .param("token", handle.leaseToken())
                    .param("expectedVersion", handle.version())
                    .param("current", current.attemptCount())
                    .update();
            if (updated != 1) {
                throw new StaleClaimException(handle.runId(), "stale reserve update on " + handle.runId());
            }
            ClaimedRun claim = new ClaimedRun(
                    handle.runId(),
                    current.mode(),
                    RunState.GENERATING,
                    newVersion,
                    new RunLease(
                            handle.leaseToken(), current.leaseOwner(), current.leaseExpiresAt()),
                    current.recoveryCount(),
                    current.replayRound(),
                    Optional.empty());
            return new ReservedGenerationAttempt(claim, next);
        });
    }

    public ClaimedRun recordModelUsage(ClaimHandle handle, io.github.patchatlas.agent.ModelUsage usage) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(usage, "usage");
        if (handle.state() != RunState.GENERATING) {
            throw new IllegalArgumentException("recordModelUsage requires GENERATING");
        }
        long newVersion = RunLeaseRules.nextVersion(handle.version());
        return tx.execute(status -> {
            int updated = jdbc.sql(
                            """
                            UPDATE verification_run
                               SET model_input_tokens = model_input_tokens + :inTok,
                                   model_output_tokens = model_output_tokens + :outTok,
                                   model_total_tokens = model_total_tokens + :totTok,
                                   model_usage_record_count = CASE
                                       WHEN model_usage_record_count IS NULL THEN NULL
                                       ELSE model_usage_record_count + 1
                                   END,
                                   version = :version,
                                   updated_at = CURRENT_TIMESTAMP
                             WHERE id = :id
                               AND state = 'GENERATING'
                               AND lease_token = :token
                               AND version = :expectedVersion
                            """)
                    .param("inTok", usage.inputTokens())
                    .param("outTok", usage.outputTokens())
                    .param("totTok", usage.totalTokens())
                    .param("version", newVersion)
                    .param("id", handle.runId())
                    .param("token", handle.leaseToken())
                    .param("expectedVersion", handle.version())
                    .update();
            if (updated != 1) {
                throw new StaleClaimException(handle.runId(), "stale usage on " + handle.runId());
            }
            ClaimRow row = loadClaimRow(handle.runId());
            Instant expiresAt = loadLeaseExpiry(handle.runId());
            return new ClaimedRun(
                    handle.runId(),
                    row.mode,
                    RunState.GENERATING,
                    newVersion,
                    new RunLease(handle.leaseToken(), loadLeaseOwner(handle.runId()), expiresAt),
                    row.recoveryCount,
                    row.replayRound,
                    Optional.empty());
        });
    }

    /**
     * Gate 通过后原子提交 candidate 并进入 {@link RunState#REPLAYING}。
     *
     * <p>仅接受 {@link GatedCandidate}，避免绕过 Patch Gate 入库。
     */
    public ClaimedRun commitCandidate(ClaimHandle handle, GatedCandidate gated) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(gated, "gated");
        PersistedCandidatePatch candidate = gated.patch();
        if (handle.state() != RunState.GENERATING) {
            throw new IllegalArgumentException("commitCandidate requires GENERATING");
        }
        if (!stateMachine.canApply(handle.state(), RunTransition.COMMIT_CANDIDATE)) {
            throw new IllegalArgumentException("illegal commitCandidate transition");
        }
        long newVersion = RunLeaseRules.nextVersion(handle.version());

        return tx.execute(status -> {
            int inserted = jdbc.sql(
                            """
                            INSERT INTO candidate_test_patch (
                              run_id, patch_text, patch_sha256, target_class, target_method
                            ) VALUES (
                              :runId, :patchText, :patchSha, :targetClass, :targetMethod
                            )
                            """)
                    .param("runId", handle.runId())
                    .param("patchText", candidate.patchText())
                    .param("patchSha", candidate.patchSha256())
                    .param("targetClass", candidate.targetTest().className())
                    .param("targetMethod", candidate.targetTest().methodName())
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException("failed to insert candidate for " + handle.runId());
            }

            int updated = jdbc.sql(
                            """
                            UPDATE verification_run
                               SET state = 'REPLAYING',
                                   version = :version,
                                   updated_at = CURRENT_TIMESTAMP
                             WHERE id = :id
                               AND state = 'GENERATING'
                               AND lease_token = :token
                               AND version = :expectedVersion
                            """)
                    .param("version", newVersion)
                    .param("id", handle.runId())
                    .param("token", handle.leaseToken())
                    .param("expectedVersion", handle.version())
                    .update();
            if (updated != 1) {
                throw new StaleClaimException(
                        handle.runId(), "stale commitCandidate on run " + handle.runId());
            }

            ClaimRow row = loadClaimRow(handle.runId());
            Instant expiresAt = loadLeaseExpiry(handle.runId());
            return new ClaimedRun(
                    handle.runId(),
                    row.mode,
                    RunState.REPLAYING,
                    newVersion,
                    new RunLease(handle.leaseToken(), loadLeaseOwner(handle.runId()), expiresAt),
                    row.recoveryCount,
                    row.replayRound,
                    Optional.of(candidate));
        });
    }

    /**
     * 进入/恢复 Replay 前递增 {@code replay_round}（同 token/version fence）。
     */
    public ClaimedRun openReplayRound(ClaimHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (handle.state() != RunState.REPLAYING) {
            throw new IllegalArgumentException("openReplayRound requires REPLAYING");
        }
        long newVersion = RunLeaseRules.nextVersion(handle.version());

        return tx.execute(status -> {
            int updated = jdbc.sql(
                            """
                            UPDATE verification_run
                               SET replay_round = replay_round + 1,
                                   version = :version,
                                   updated_at = CURRENT_TIMESTAMP
                             WHERE id = :id
                               AND state = 'REPLAYING'
                               AND lease_token = :token
                               AND version = :expectedVersion
                            """)
                    .param("version", newVersion)
                    .param("id", handle.runId())
                    .param("token", handle.leaseToken())
                    .param("expectedVersion", handle.version())
                    .update();
            if (updated != 1) {
                throw new StaleClaimException(
                        handle.runId(), "stale openReplayRound on run " + handle.runId());
            }
            ClaimRow row = loadClaimRow(handle.runId());
            Instant expiresAt = loadLeaseExpiry(handle.runId());
            PersistedCandidatePatch candidate = loadCandidateRequired(handle.runId());
            return new ClaimedRun(
                    handle.runId(),
                    row.mode,
                    RunState.REPLAYING,
                    newVersion,
                    new RunLease(handle.leaseToken(), loadLeaseOwner(handle.runId()), expiresAt),
                    row.recoveryCount,
                    row.replayRound,
                    Optional.of(candidate));
        });
    }

    /**
     * 原子写入 attempts + 聚合事实 + {@code COMPLETED}。
     *
     * <p>中途失败则整事务回滚。
     */
    public RunDetails complete(ClaimHandle handle, ReplayResult result) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(result, "result");
        if (handle.state() != RunState.REPLAYING) {
            throw new IllegalArgumentException("complete requires REPLAYING");
        }
        if (!stateMachine.canApply(handle.state(), RunTransition.COMPLETE)) {
            throw new IllegalArgumentException("illegal complete transition");
        }
        TerminalRunRules.requireCompleted(result.verdict(), null);
        long newVersion = RunLeaseRules.nextVersion(handle.version());

        return tx.execute(status -> {
            ClaimRow row = loadClaimRow(handle.runId());
            if (row == null || row.replayRound < 1) {
                throw new IllegalStateException(
                        "complete requires openReplayRound first: " + handle.runId());
            }
            PersistedCandidatePatch candidate = loadCandidateRequired(handle.runId());
            if (!candidate.targetTest().equals(result.targetTest())) {
                throw new IllegalArgumentException("ReplayResult targetTest must match committed candidate");
            }
            requireModeAlignment(row.mode, result);

            insertSideAttempts(
                    handle.runId(), row.replayRound, ReplaySide.PRIMARY, result.primarySide(), result.targetTest());
            if (result.fixedSide().isPresent()) {
                insertSideAttempts(
                        handle.runId(),
                        row.replayRound,
                        ReplaySide.FIXED,
                        result.fixedSide().orElseThrow(),
                        result.targetTest());
            }

            int updated = jdbc.sql(
                            """
                            UPDATE verification_run
                               SET state = 'COMPLETED',
                                   lease_token = NULL,
                                   lease_owner = NULL,
                                   lease_expires_at = NULL,
                                   verdict = :verdict,
                                   primary_stable_evidence = :primaryStable,
                                   primary_aggregated_outcome = :primaryAgg,
                                   fixed_stable_evidence = :fixedStable,
                                   fixed_aggregated_outcome = :fixedAgg,
                                   fixed_not_executed_reason = :fixedReason,
                                   final_replay_round = :finalRound,
                                   failure_stage = NULL,
                                   failure_category = NULL,
                                   failure_summary = NULL,
                                   version = :version,
                                   completed_at = CURRENT_TIMESTAMP,
                                   updated_at = CURRENT_TIMESTAMP
                             WHERE id = :id
                               AND state = 'REPLAYING'
                               AND lease_token = :token
                               AND version = :expectedVersion
                            """)
                    .param("verdict", result.verdict().name())
                    .param("primaryStable", result.primarySide().stableEvidence().name())
                    .param(
                            "primaryAgg",
                            result.primarySide().aggregatedOutcome().map(Enum::name).orElse(null))
                    .param(
                            "fixedStable",
                            result.fixedSide()
                                    .map(s -> s.stableEvidence().name())
                                    .orElse(null))
                    .param(
                            "fixedAgg",
                            result.fixedSide()
                                    .flatMap(SideExecutionResult::aggregatedOutcome)
                                    .map(Enum::name)
                                    .orElse(null))
                    .param("fixedReason", result.fixedNotExecutedReason().orElse(null))
                    .param("finalRound", row.replayRound)
                    .param("version", newVersion)
                    .param("id", handle.runId())
                    .param("token", handle.leaseToken())
                    .param("expectedVersion", handle.version())
                    .update();
            if (updated != 1) {
                throw new StaleClaimException(handle.runId(), "stale complete on run " + handle.runId());
            }
            return findRun(handle.runId())
                    .orElseThrow(() -> new IllegalStateException("run disappeared after complete"));
        });
    }

    /** 从 final_replay_round 的 attempts 重建 {@link ReplayResult}。 */
    public ReplayResult loadReplayResult(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        RunDetails details = findRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        if (details.state() != RunState.COMPLETED) {
            throw new IllegalStateException("loadReplayResult requires COMPLETED run");
        }
        int finalRound = jdbc.sql(
                        """
                        SELECT final_replay_round FROM verification_run WHERE id = :id
                        """)
                .param("id", runId)
                .query(Integer.class)
                .single();
        TargetTest target = details.candidate()
                .orElseThrow(() -> new IllegalStateException("COMPLETED run missing candidate"))
                .targetTest();

        List<AttemptRecordCodec.PersistedAttempt> rows = loadAttemptRows(runId, finalRound);
        SideExecutionResult primary = rebuildSide(rows, ReplaySide.PRIMARY, target);
        Optional<SideExecutionResult> fixed = Optional.empty();
        boolean hasFixed = rows.stream().anyMatch(r -> r.side() == ReplaySide.FIXED);
        if (hasFixed) {
            fixed = Optional.of(rebuildSide(rows, ReplaySide.FIXED, target));
        }

        String fixedReason = jdbc.sql(
                        """
                        SELECT fixed_not_executed_reason FROM verification_run WHERE id = :id
                        """)
                .param("id", runId)
                .query(String.class)
                .optional()
                .orElse(null);

        return new ReplayResult(
                details.mode(),
                details.verdict().orElseThrow(),
                target,
                primary,
                fixed,
                Optional.ofNullable(fixedReason));
    }

    /**
     * 终态失败：匹配 token + version + expected state；清空 lease。
     */
    public RunDetails fail(ClaimHandle handle, RunFailure failure) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(failure, "failure");
        if (!stateMachine.canApply(handle.state(), RunTransition.FAIL)) {
            throw new IllegalArgumentException("cannot fail from " + handle.state());
        }
        long newVersion = RunLeaseRules.nextVersion(handle.version());

        return tx.execute(status -> {
            int updated = jdbc.sql(
                            """
                            UPDATE verification_run
                               SET state = 'FAILED',
                                   lease_token = NULL,
                                   lease_owner = NULL,
                                   lease_expires_at = NULL,
                                   failure_stage = :stage,
                                   failure_category = :category,
                                   failure_summary = :summary,
                                   version = :version,
                                   completed_at = CURRENT_TIMESTAMP,
                                   updated_at = CURRENT_TIMESTAMP
                             WHERE id = :id
                               AND state = :state
                               AND lease_token = :token
                               AND version = :expectedVersion
                            """)
                    .param("stage", failure.stage().name())
                    .param("category", failure.category().name())
                    .param("summary", failure.summary())
                    .param("version", newVersion)
                    .param("id", handle.runId())
                    .param("state", handle.state().name())
                    .param("token", handle.leaseToken())
                    .param("expectedVersion", handle.version())
                    .update();
            if (updated != 1) {
                throw new StaleClaimException(handle.runId(), "stale fail on run " + handle.runId());
            }
            return findRun(handle.runId())
                    .orElseThrow(() -> new IllegalStateException("run disappeared after fail"));
        });
    }

    private Optional<ClaimedRun> claimNextInTransaction(
            String owner, long leaseSeconds, java.util.List<RunDetails> exhausted) {
        // 有界重试：recovery 耗尽会消耗一行，继续找下一候选
        for (int attempt = 0; attempt < 32; attempt++) {
            Optional<UUID> candidateId = selectClaimCandidate();
            if (candidateId.isEmpty()) {
                return Optional.empty();
            }
            UUID runId = candidateId.get();
            ClaimRow row = loadClaimRow(runId);
            if (row == null) {
                continue;
            }

            if (row.state == RunState.QUEUED) {
                return Optional.of(claimQueued(row, owner, leaseSeconds));
            }

            // 过期接管
            if (!RunLeaseRules.canReclaim(row.recoveryCount)) {
                markRecoveryExhausted(row);
                findRun(row.id).ifPresent(exhausted::add);
                continue;
            }
            return Optional.of(reclaimExpired(row, owner, leaseSeconds));
        }
        return Optional.empty();
    }

    private Optional<UUID> selectClaimCandidate() {
        return jdbc.sql(
                        """
                        SELECT id
                          FROM verification_run
                         WHERE state = 'QUEUED'
                            OR (state IN ('GENERATING', 'REPLAYING')
                                AND lease_expires_at IS NOT NULL
                                AND lease_expires_at < CURRENT_TIMESTAMP)
                         ORDER BY created_at ASC, id ASC
                         FOR UPDATE SKIP LOCKED
                         LIMIT 1
                        """)
                .query(UUID.class)
                .optional();
    }

    private ClaimedRun claimQueued(ClaimRow row, String owner, long leaseSeconds) {
        if (!stateMachine.canApply(row.state, RunTransition.CLAIM)) {
            throw new IllegalStateException("cannot claim from " + row.state);
        }
        UUID token = UUID.randomUUID();
        long newVersion = RunLeaseRules.nextVersion(row.version);
        int updated = jdbc.sql(
                        """
                        UPDATE verification_run
                           SET state = 'GENERATING',
                               lease_token = :token,
                               lease_owner = :owner,
                               lease_expires_at = CURRENT_TIMESTAMP + make_interval(secs => :secs),
                               version = :version,
                               started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = :id
                           AND state = 'QUEUED'
                           AND version = :expectedVersion
                        """)
                .param("token", token)
                .param("owner", owner)
                .param("secs", leaseSeconds)
                .param("version", newVersion)
                .param("id", row.id)
                .param("expectedVersion", row.version)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("stale claim on QUEUED run " + row.id);
        }
        Instant expiresAt = loadLeaseExpiry(row.id);
        return new ClaimedRun(
                row.id,
                row.mode,
                RunState.GENERATING,
                newVersion,
                new RunLease(token, owner, expiresAt),
                row.recoveryCount,
                row.replayRound,
                Optional.empty());
    }

    private ClaimedRun reclaimExpired(ClaimRow row, String owner, long leaseSeconds) {
        if (!stateMachine.canApply(row.state, RunTransition.RECLAIM)) {
            throw new IllegalStateException("cannot reclaim from " + row.state);
        }
        int newRecovery = RunLeaseRules.nextRecoveryCount(row.recoveryCount);
        UUID token = UUID.randomUUID();
        long newVersion = RunLeaseRules.nextVersion(row.version);
        int updated = jdbc.sql(
                        """
                        UPDATE verification_run
                           SET lease_token = :token,
                               lease_owner = :owner,
                               lease_expires_at = CURRENT_TIMESTAMP + make_interval(secs => :secs),
                               recovery_count = :recoveryCount,
                               version = :version,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = :id
                           AND state = :state
                           AND version = :expectedVersion
                           AND lease_expires_at IS NOT NULL
                           AND lease_expires_at < CURRENT_TIMESTAMP
                        """)
                .param("token", token)
                .param("owner", owner)
                .param("secs", leaseSeconds)
                .param("recoveryCount", newRecovery)
                .param("version", newVersion)
                .param("id", row.id)
                .param("state", row.state.name())
                .param("expectedVersion", row.version)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("stale reclaim on run " + row.id);
        }

        Optional<PersistedCandidatePatch> candidate = Optional.empty();
        if (row.state == RunState.REPLAYING) {
            candidate = Optional.of(loadCandidateRequired(row.id));
        }
        Instant expiresAt = loadLeaseExpiry(row.id);
        return new ClaimedRun(
                row.id,
                row.mode,
                row.state,
                newVersion,
                new RunLease(token, owner, expiresAt),
                newRecovery,
                row.replayRound,
                candidate);
    }

    private void markRecoveryExhausted(ClaimRow row) {
        RunFailure failure = new RunFailure(
                FailureStage.RECOVERY,
                FailureCategory.RECOVERY_EXHAUSTED,
                "recovery count exhausted");
        jdbc.sql(
                        """
                        UPDATE verification_run
                           SET state = 'FAILED',
                               lease_token = NULL,
                               lease_owner = NULL,
                               lease_expires_at = NULL,
                               failure_stage = :stage,
                               failure_category = :category,
                               failure_summary = :summary,
                               version = :version,
                               completed_at = CURRENT_TIMESTAMP,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = :id
                           AND state IN ('GENERATING', 'REPLAYING')
                           AND version = :expectedVersion
                        """)
                .param("stage", failure.stage().name())
                .param("category", failure.category().name())
                .param("summary", failure.summary())
                .param("version", RunLeaseRules.nextVersion(row.version))
                .param("id", row.id)
                .param("expectedVersion", row.version)
                .update();
    }

    /** 从当前 handle 重建 ClaimedRun（含 candidate）。 */
    public Optional<ClaimedRun> findClaimed(ClaimHandle handle) {
        Objects.requireNonNull(handle, "handle");
        ClaimRow row = loadClaimRow(handle.runId());
        if (row == null || row.state != handle.state() || row.version != handle.version()) {
            return Optional.empty();
        }
        Instant expiresAt;
        String owner;
        try {
            expiresAt = loadLeaseExpiry(handle.runId());
            owner = loadLeaseOwner(handle.runId());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        Optional<PersistedCandidatePatch> candidate = Optional.empty();
        if (row.state == RunState.REPLAYING) {
            candidate = Optional.of(loadCandidateRequired(handle.runId()));
        }
        return Optional.of(new ClaimedRun(
                row.id,
                row.mode,
                row.state,
                row.version,
                new RunLease(handle.leaseToken(), owner, expiresAt),
                row.recoveryCount,
                row.replayRound,
                candidate));
    }

    private ClaimRow loadClaimRow(UUID id) {
        return jdbc.sql(
                        """
                        SELECT id, mode, state, version, recovery_count, replay_round
                          FROM verification_run
                         WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new ClaimRow(
                        (UUID) rs.getObject("id"),
                        VerificationMode.valueOf(rs.getString("mode")),
                        RunState.valueOf(rs.getString("state")),
                        rs.getLong("version"),
                        rs.getInt("recovery_count"),
                        rs.getInt("replay_round")))
                .optional()
                .orElse(null);
    }

    private Instant loadLeaseExpiry(UUID id) {
        Timestamp ts = jdbc.sql(
                        """
                        SELECT lease_expires_at FROM verification_run WHERE id = :id
                        """)
                .param("id", id)
                .query(Timestamp.class)
                .single();
        return ts.toInstant();
    }

    private String loadLeaseOwner(UUID id) {
        return jdbc.sql(
                        """
                        SELECT lease_owner FROM verification_run WHERE id = :id
                        """)
                .param("id", id)
                .query(String.class)
                .single();
    }

    private void insertSideAttempts(
            UUID runId,
            int replayRound,
            ReplaySide side,
            SideExecutionResult sideResult,
            TargetTest target) {
        List<AttemptRecord> attempts = sideResult.attempts();
        for (int i = 0; i < attempts.size(); i++) {
            AttemptRecordCodec.PersistedAttempt row = attemptCodec.encode(
                    UUID.randomUUID(), runId, replayRound, side, i + 1, attempts.get(i));
            // 再 decode 一次确认可重建（fail closed before insert）
            attemptCodec.decode(row, target);
            insertAttemptRow(row);
        }
    }

    private void insertAttemptRow(AttemptRecordCodec.PersistedAttempt row) {
        jdbc.sql(
                        """
                        INSERT INTO replay_attempt (
                          id, run_id, replay_round, side, attempt_ordinal,
                          phase, outcome, target_evidence, diagnostic,
                          sandbox_status, exit_code, elapsed_ms, timed_out,
                          command, log_summary, image, limits, network_mode,
                          test_cases, evidence_schema_version
                        ) VALUES (
                          :id, :runId, :round, :side, :ordinal,
                          :phase, :outcome, :evidence, :diagnostic,
                          :sandboxStatus, :exitCode, :elapsedMs, :timedOut,
                          CAST(:command AS jsonb), :logSummary, :image, CAST(:limits AS jsonb), :networkMode,
                          CAST(:testCases AS jsonb), :schemaVersion
                        )
                        """)
                .param("id", row.id())
                .param("runId", row.runId())
                .param("round", row.replayRound())
                .param("side", row.side().name())
                .param("ordinal", row.attemptOrdinal())
                .param("phase", row.phase().name())
                .param("outcome", row.outcome() == null ? null : row.outcome().name())
                .param("evidence", row.targetEvidence().name())
                .param("diagnostic", row.diagnostic())
                .param(
                        "sandboxStatus",
                        row.sandboxStatus() == null ? null : row.sandboxStatus().name())
                .param("exitCode", row.exitCode())
                .param("elapsedMs", row.elapsedMs())
                .param("timedOut", row.timedOut())
                .param("command", row.commandJson())
                .param("logSummary", row.logSummary())
                .param("image", row.image())
                .param("limits", row.limitsJson())
                .param("networkMode", row.networkMode())
                .param("testCases", row.testCasesJson())
                .param("schemaVersion", row.evidenceSchemaVersion())
                .update();
    }

    private List<AttemptRecordCodec.PersistedAttempt> loadAttemptRows(UUID runId, int round) {
        return jdbc.sql(
                        """
                        SELECT id, run_id, replay_round, side, attempt_ordinal,
                               phase, outcome, target_evidence, diagnostic,
                               sandbox_status, exit_code, elapsed_ms, timed_out,
                               command::text AS command, log_summary, image,
                               limits::text AS limits, network_mode,
                               test_cases::text AS test_cases, evidence_schema_version
                          FROM replay_attempt
                         WHERE run_id = :runId AND replay_round = :round
                         ORDER BY side ASC, attempt_ordinal ASC
                        """)
                .param("runId", runId)
                .param("round", round)
                .query((rs, rowNum) -> mapAttemptRow(rs))
                .list();
    }

    private AttemptRecordCodec.PersistedAttempt mapAttemptRow(ResultSet rs) throws SQLException {
        String outcome = rs.getString("outcome");
        String sandbox = rs.getString("sandbox_status");
        Boolean timedOut = rs.getObject("timed_out") == null ? null : rs.getBoolean("timed_out");
        Long elapsed = rs.getObject("elapsed_ms") == null ? null : rs.getLong("elapsed_ms");
        Integer exitCode = rs.getObject("exit_code") == null ? null : rs.getInt("exit_code");
        return new AttemptRecordCodec.PersistedAttempt(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("run_id"),
                rs.getInt("replay_round"),
                ReplaySide.valueOf(rs.getString("side")),
                rs.getInt("attempt_ordinal"),
                AttemptPhase.valueOf(rs.getString("phase")),
                outcome == null ? null : RunOutcome.valueOf(outcome),
                SingleAttemptEvidence.valueOf(rs.getString("target_evidence")),
                rs.getString("diagnostic"),
                sandbox == null ? null : SandboxExecutionStatus.valueOf(sandbox),
                exitCode,
                elapsed,
                timedOut,
                rs.getString("command"),
                rs.getString("log_summary"),
                rs.getString("image"),
                rs.getString("limits"),
                rs.getString("network_mode"),
                rs.getString("test_cases"),
                rs.getShort("evidence_schema_version"));
    }

    private SideExecutionResult rebuildSide(
            List<AttemptRecordCodec.PersistedAttempt> rows, ReplaySide side, TargetTest target) {
        List<AttemptRecordCodec.PersistedAttempt> sideRows = rows.stream()
                .filter(r -> r.side() == side)
                .sorted((a, b) -> Integer.compare(a.attemptOrdinal(), b.attemptOrdinal()))
                .toList();
        if (sideRows.size() != 2) {
            throw new IllegalStateException(side + " must have exactly 2 attempts, got " + sideRows.size());
        }
        if (sideRows.get(0).attemptOrdinal() != 1 || sideRows.get(1).attemptOrdinal() != 2) {
            throw new IllegalStateException(side + " attempt ordinals must be 1 and 2");
        }
        List<AttemptRecord> attempts = new ArrayList<>(2);
        attempts.add(attemptCodec.decode(sideRows.get(0), target));
        attempts.add(attemptCodec.decode(sideRows.get(1), target));

        return new SideExecutionResult(attempts);
    }

    private static void requireModeAlignment(VerificationMode runMode, ReplayResult result) {
        if (runMode != result.mode()) {
            throw new IllegalArgumentException(
                    "ReplayResult mode " + result.mode() + " does not match run mode " + runMode);
        }
    }

    private PersistedCandidatePatch loadCandidateRequired(UUID runId) {
        return jdbc.sql(
                        """
                        SELECT patch_text, patch_sha256, target_class, target_method
                          FROM candidate_test_patch
                         WHERE run_id = :id
                        """)
                .param("id", runId)
                .query((rs, rowNum) -> PersistedCandidatePatch.restore(
                        rs.getString("patch_text"),
                        rs.getString("patch_sha256"),
                        rs.getString("target_class"),
                        rs.getString("target_method")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("missing candidate for REPLAYING run " + runId));
    }

    private RunDetails mapDetails(ResultSet rs) throws SQLException {
        RunState state = RunState.valueOf(rs.getString("state"));
        VerificationMode mode = VerificationMode.valueOf(rs.getString("mode"));
        Optional<ReplayVerdict> verdict = Optional.ofNullable(rs.getString("verdict"))
                .map(ReplayVerdict::valueOf);
        Optional<RunFailure> failure = Optional.empty();
        String stage = rs.getString("failure_stage");
        if (stage != null) {
            failure = Optional.of(new RunFailure(
                    FailureStage.valueOf(stage),
                    FailureCategory.valueOf(rs.getString("failure_category")),
                    rs.getString("failure_summary")));
        }
        Optional<PersistedCandidatePatch> candidate = Optional.empty();
        String patchText = rs.getString("patch_text");
        if (patchText != null) {
            candidate = Optional.of(PersistedCandidatePatch.restore(
                    patchText,
                    rs.getString("patch_sha256"),
                    rs.getString("target_class"),
                    rs.getString("target_method")));
        }
        Instant completedAt = toInstant(rs.getTimestamp("completed_at"));
        return new RunDetails(
                (UUID) rs.getObject("id"),
                mode,
                state,
                rs.getLong("version"),
                rs.getString("case_id"),
                rs.getString("repository_url"),
                rs.getString("issue_title"),
                rs.getString("buggy_revision"),
                rs.getString("fixed_revision"),
                verdict,
                failure,
                candidate,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completedAt);
    }

    private IdempotentSubmitResult loadIdempotentResult(IdempotencyKey key, String submissionSha256) {
        var row = jdbc.sql(
                        """
                        SELECT id, state, submission_sha256
                          FROM verification_run
                         WHERE idempotency_key = :key
                        """)
                .param("key", key.value())
                .query((rs, rowNum) -> new Object[] {
                    (UUID) rs.getObject("id"),
                    RunState.valueOf(rs.getString("state")),
                    rs.getString("submission_sha256")
                })
                .optional()
                .orElseThrow(() -> new IllegalStateException("idempotency key vanished after conflict"));
        UUID id = (UUID) row[0];
        RunState state = (RunState) row[1];
        String stored = (String) row[2];
        if (submissionSha256.equals(stored)) {
            return new IdempotentSubmitResult.Accepted(id, state, false);
        }
        return new IdempotentSubmitResult.Conflict(id);
    }

    private RunSummary mapSummary(ResultSet rs) throws SQLException {
        Optional<ReplayVerdict> verdict = Optional.ofNullable(rs.getString("verdict")).map(ReplayVerdict::valueOf);
        Optional<FailureCategory> failureCategory =
                Optional.ofNullable(rs.getString("failure_category")).map(FailureCategory::valueOf);
        return new RunSummary(
                (UUID) rs.getObject("id"),
                VerificationMode.valueOf(rs.getString("mode")),
                RunState.valueOf(rs.getString("state")),
                rs.getString("issue_title"),
                rs.getString("repository_url"),
                verdict,
                failureCategory,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                toInstant(rs.getTimestamp("completed_at")));
    }

    private RunDetailView mapDetailHeader(ResultSet rs) throws SQLException {
        RunState state = RunState.valueOf(rs.getString("state"));
        Optional<ReplayVerdict> verdict = Optional.ofNullable(rs.getString("verdict")).map(ReplayVerdict::valueOf);
        Optional<RunFailure> failure = Optional.empty();
        if (rs.getString("failure_stage") != null) {
            failure = Optional.of(new RunFailure(
                    FailureStage.valueOf(rs.getString("failure_stage")),
                    FailureCategory.valueOf(rs.getString("failure_category")),
                    rs.getString("failure_summary")));
        }
        Optional<RunDetailView.CandidateView> candidate = Optional.empty();
        String patchText = rs.getString("patch_text");
        if (patchText != null) {
            candidate = Optional.of(new RunDetailView.CandidateView(
                    patchText,
                    rs.getString("patch_sha256"),
                    new TargetTest(rs.getString("target_class"), rs.getString("target_method"))));
        }
        return new RunDetailView(
                (UUID) rs.getObject("id"),
                VerificationMode.valueOf(rs.getString("mode")),
                state,
                rs.getString("case_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                toInstant(rs.getTimestamp("completed_at")),
                new RunDetailView.InputSummary(
                        rs.getString("repository_url"),
                        rs.getString("issue_url"),
                        rs.getString("issue_title"),
                        rs.getString("issue_body"),
                        rs.getString("buggy_revision"),
                        rs.getString("fixed_revision"),
                        rs.getString("module_path")),
                new MavenExecutionPolicy(
                        rs.getString("java_version"),
                        MavenNetworkMode.valueOf(rs.getString("network_mode"))),
                new RunDetailView.GenerationMeta(
                        rs.getInt("generation_attempt_count"),
                        rs.getString("model_provider"),
                        rs.getString("model_name"),
                        rs.getLong("model_input_tokens"),
                        rs.getLong("model_output_tokens"),
                        rs.getLong("model_total_tokens"),
                        nullableInteger(rs, "model_usage_record_count")),
                candidate,
                verdict,
                failure,
                List.of());
    }

    private List<RunAttemptView> loadAttemptViews(UUID runId, Optional<TargetTest> target) {
        // 在 PostgreSQL 侧只投影匹配 Target Test 的单个 JSON 对象，避免把整包
        // test_cases（可能很大）拉进 JVM 再过滤。
        if (target.isEmpty()) {
            return jdbc.sql(
                            """
                            SELECT replay_round, side, attempt_ordinal, phase, outcome,
                                   target_evidence, diagnostic, sandbox_status, exit_code,
                                   elapsed_ms, timed_out, command::text AS command, log_summary,
                                   image, limits::text AS limits, network_mode,
                                   NULL::text AS matched_test_case, evidence_schema_version
                              FROM replay_attempt
                             WHERE run_id = :runId
                             ORDER BY replay_round ASC, side ASC, attempt_ordinal ASC
                            """)
                    .param("runId", runId)
                    .query((rs, rowNum) -> mapAttemptView(rs))
                    .list();
        }
        TargetTest t = target.orElseThrow();
        return jdbc.sql(
                        """
                        SELECT replay_round, side, attempt_ordinal, phase, outcome,
                               target_evidence, diagnostic, sandbox_status, exit_code,
                               elapsed_ms, timed_out, command::text AS command, log_summary,
                               image, limits::text AS limits, network_mode,
                               (
                                 SELECT e
                                   FROM jsonb_array_elements(COALESCE(test_cases, '[]'::jsonb)) AS e
                                  WHERE e->>'className' = :targetClass
                                    AND e->>'methodName' = :targetMethod
                                  LIMIT 1
                               )::text AS matched_test_case,
                               evidence_schema_version
                          FROM replay_attempt
                         WHERE run_id = :runId
                         ORDER BY replay_round ASC, side ASC, attempt_ordinal ASC
                        """)
                .param("runId", runId)
                .param("targetClass", t.className())
                .param("targetMethod", t.methodName())
                .query((rs, rowNum) -> mapAttemptView(rs))
                .list();
    }

    private RunAttemptView mapAttemptView(ResultSet rs) throws SQLException {
        String outcome = rs.getString("outcome");
        String sandbox = rs.getString("sandbox_status");
        Optional<RunAttemptView.TargetTestCaseView> matched =
                parseMatchedTargetCase(rs.getString("matched_test_case"));
        return new RunAttemptView(
                rs.getInt("replay_round"),
                ReplaySide.valueOf(rs.getString("side")),
                rs.getInt("attempt_ordinal"),
                AttemptPhase.valueOf(rs.getString("phase")),
                Optional.ofNullable(outcome).map(RunOutcome::valueOf),
                SingleAttemptEvidence.valueOf(rs.getString("target_evidence")),
                Optional.ofNullable(rs.getString("diagnostic")),
                Optional.ofNullable(sandbox).map(SandboxExecutionStatus::valueOf),
                Optional.ofNullable(rs.getObject("exit_code")).map(v -> (Integer) v),
                Optional.ofNullable(rs.getObject("elapsed_ms")).map(v -> ((Number) v).longValue()),
                Optional.ofNullable(rs.getObject("timed_out")).map(v -> (Boolean) v),
                Optional.ofNullable(rs.getString("command")),
                Optional.ofNullable(rs.getString("image")),
                Optional.ofNullable(rs.getString("limits")),
                Optional.ofNullable(rs.getString("network_mode")),
                Optional.ofNullable(rs.getString("log_summary")),
                matched,
                rs.getShort("evidence_schema_version"));
    }

    /** 解析 PostgreSQL 已投影的单个 Target Test Case 对象（非全量数组）。 */
    private static Optional<RunAttemptView.TargetTestCaseView> parseMatchedTargetCase(
            String matchedJson) {
        if (matchedJson == null || matchedJson.isBlank()) {
            return Optional.empty();
        }
        try {
            tools.jackson.databind.JsonNode n = tools.jackson.databind.json.JsonMapper.shared()
                    .readTree(matchedJson);
            if (n == null || !n.isObject()) {
                return Optional.empty();
            }
            tools.jackson.databind.JsonNode cn = n.get("className");
            tools.jackson.databind.JsonNode mn = n.get("methodName");
            if (cn == null || mn == null || !cn.isString() || !mn.isString()) {
                return Optional.empty();
            }
            String className = cn.stringValue();
            String methodName = mn.stringValue();
            tools.jackson.databind.JsonNode st = n.get("status");
            String status = st != null && st.isString() ? st.stringValue() : "UNKNOWN";
            tools.jackson.databind.JsonNode msg = n.get("message");
            String message = msg == null || msg.isNull() || !msg.isString() ? null : msg.stringValue();
            tools.jackson.databind.JsonNode elapsedNode = n.get("elapsedMs");
            Long elapsedMs = null;
            if (elapsedNode != null && !elapsedNode.isNull() && elapsedNode.isNumber()) {
                elapsedMs = elapsedNode.longValue();
            }
            tools.jackson.databind.JsonNode exNode = n.get("exceptionType");
            String exceptionType =
                    exNode == null || exNode.isNull() || !exNode.isString()
                            ? null
                            : exNode.stringValue();
            return Optional.of(new RunAttemptView.TargetTestCaseView(
                    className,
                    methodName,
                    status,
                    Optional.ofNullable(message),
                    Optional.ofNullable(elapsedMs),
                    Optional.ofNullable(exceptionType)));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return ((Number) value).intValue();
    }

    private record ClaimRow(
            UUID id,
            VerificationMode mode,
            RunState state,
            long version,
            int recoveryCount,
            int replayRound) {}

    private record GenerationReservationRow(
            VerificationMode mode,
            int attemptCount,
            int recoveryCount,
            int replayRound,
            String leaseOwner,
            Instant leaseExpiresAt) {}
}
