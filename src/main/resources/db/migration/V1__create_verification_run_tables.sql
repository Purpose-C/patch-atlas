-- Three business tables only. Flyway history is separate.

CREATE TABLE verification_run (
    id                          UUID PRIMARY KEY,
    mode                        VARCHAR(16)  NOT NULL,
    case_id                     VARCHAR(128),
    repository_url              VARCHAR(2048) NOT NULL,
    license                     VARCHAR(128),
    issue_url                   VARCHAR(2048),
    issue_title                 TEXT         NOT NULL,
    issue_body                  TEXT         NOT NULL,
    buggy_revision              CHAR(40)     NOT NULL,
    fixed_revision              CHAR(40),
    module_path                 VARCHAR(512) NOT NULL DEFAULT '',
    java_version                VARCHAR(32),
    source_snapshots            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    input_schema_version        SMALLINT     NOT NULL DEFAULT 1,

    state                       VARCHAR(16)  NOT NULL,
    version                     BIGINT       NOT NULL DEFAULT 0,
    lease_token                 UUID,
    lease_owner                 VARCHAR(128),
    lease_expires_at            TIMESTAMPTZ,
    recovery_count              SMALLINT     NOT NULL DEFAULT 0,
    replay_round                INTEGER      NOT NULL DEFAULT 0,

    verdict                     VARCHAR(32),
    primary_stable_evidence     VARCHAR(32),
    primary_aggregated_outcome  VARCHAR(32),
    fixed_stable_evidence       VARCHAR(32),
    fixed_aggregated_outcome    VARCHAR(32),
    fixed_not_executed_reason   VARCHAR(512),
    final_replay_round          INTEGER,

    failure_stage               VARCHAR(32),
    failure_category            VARCHAR(64),
    failure_summary             VARCHAR(512),

    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at                  TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ,

    CONSTRAINT verification_run_mode_chk
        CHECK (mode IN ('LIVE', 'HISTORICAL')),
    CONSTRAINT verification_run_state_chk
        CHECK (state IN ('QUEUED', 'GENERATING', 'REPLAYING', 'COMPLETED', 'FAILED')),
    CONSTRAINT verification_run_version_chk
        CHECK (version >= 0),
    CONSTRAINT verification_run_recovery_count_chk
        CHECK (recovery_count >= 0),
    CONSTRAINT verification_run_replay_round_chk
        CHECK (replay_round >= 0),
    CONSTRAINT verification_run_input_schema_chk
        CHECK (input_schema_version = 1),
    CONSTRAINT verification_run_buggy_revision_chk
        CHECK (buggy_revision ~ '^[0-9a-f]{40}$'),
    CONSTRAINT verification_run_fixed_revision_format_chk
        CHECK (fixed_revision IS NULL OR fixed_revision ~ '^[0-9a-f]{40}$'),
    CONSTRAINT verification_run_mode_fixed_chk
        CHECK (
            (mode = 'LIVE' AND fixed_revision IS NULL)
            OR (mode = 'HISTORICAL' AND fixed_revision IS NOT NULL)
        ),
    CONSTRAINT verification_run_issue_size_chk
        CHECK (char_length(issue_title) + char_length(issue_body) <= 32768),
    CONSTRAINT verification_run_snapshots_array_chk
        CHECK (jsonb_typeof(source_snapshots) = 'array'),
    CONSTRAINT verification_run_snapshots_count_chk
        CHECK (jsonb_array_length(source_snapshots) <= 12),
    -- 内容合计 256 KiB；JSON 编码开销预留至 384 KiB 文本上限
    CONSTRAINT verification_run_snapshots_size_chk
        CHECK (octet_length(source_snapshots::text) <= 393216),
    CONSTRAINT verification_run_lease_shape_chk
        CHECK (
            (state = 'QUEUED'
                AND lease_token IS NULL
                AND lease_owner IS NULL
                AND lease_expires_at IS NULL)
            OR (state IN ('GENERATING', 'REPLAYING')
                AND lease_token IS NOT NULL
                AND lease_owner IS NOT NULL
                AND lease_expires_at IS NOT NULL)
            OR (state IN ('COMPLETED', 'FAILED')
                AND lease_token IS NULL
                AND lease_owner IS NULL
                AND lease_expires_at IS NULL)
        ),
    CONSTRAINT verification_run_completed_shape_chk
        CHECK (
            state <> 'COMPLETED'
            OR (
                verdict IS NOT NULL
                AND primary_stable_evidence IS NOT NULL
                AND final_replay_round IS NOT NULL
                AND failure_stage IS NULL
                AND failure_category IS NULL
                AND failure_summary IS NULL
                AND completed_at IS NOT NULL
            )
        ),
    CONSTRAINT verification_run_failed_shape_chk
        CHECK (
            state <> 'FAILED'
            OR (
                failure_stage IS NOT NULL
                AND failure_category IS NOT NULL
                AND failure_summary IS NOT NULL
                AND verdict IS NULL
                AND completed_at IS NOT NULL
            )
        ),
    CONSTRAINT verification_run_nonterminal_completed_at_chk
        CHECK (
            (state IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)
            OR (state NOT IN ('COMPLETED', 'FAILED') AND completed_at IS NULL)
        ),
    CONSTRAINT verification_run_timestamps_chk
        CHECK (
            updated_at >= created_at
            AND (completed_at IS NULL OR completed_at >= created_at)
        )
);

CREATE INDEX verification_run_claim_idx
    ON verification_run (created_at ASC, id ASC)
    WHERE state = 'QUEUED'
       OR (state IN ('GENERATING', 'REPLAYING'));

CREATE INDEX verification_run_lease_expiry_idx
    ON verification_run (lease_expires_at)
    WHERE state IN ('GENERATING', 'REPLAYING');

CREATE TABLE candidate_test_patch (
    run_id          UUID PRIMARY KEY
                    REFERENCES verification_run (id),
    patch_text      TEXT         NOT NULL,
    patch_sha256    CHAR(64)     NOT NULL,
    target_class    VARCHAR(256) NOT NULL,
    target_method   VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT candidate_test_patch_text_nonempty_chk
        CHECK (char_length(patch_text) > 0),
    CONSTRAINT candidate_test_patch_size_chk
        CHECK (octet_length(patch_text) <= 65536),
    CONSTRAINT candidate_test_patch_no_nul_chk
        CHECK (position(E'\\x00' IN patch_text) = 0),
    CONSTRAINT candidate_test_patch_sha_chk
        CHECK (patch_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT candidate_test_patch_selector_chk
        CHECK (char_length(target_class) + 1 + char_length(target_method) <= 256)
);

CREATE TABLE replay_attempt (
    id                      UUID PRIMARY KEY,
    run_id                  UUID         NOT NULL
                            REFERENCES verification_run (id),
    replay_round            INTEGER      NOT NULL,
    side                    VARCHAR(16)  NOT NULL,
    attempt_ordinal         SMALLINT     NOT NULL,

    phase                   VARCHAR(32)  NOT NULL,
    outcome                 VARCHAR(32),
    target_evidence         VARCHAR(32)  NOT NULL,
    diagnostic              VARCHAR(512),

    sandbox_status          VARCHAR(64),
    exit_code               INTEGER,
    elapsed_ms              BIGINT,
    timed_out               BOOLEAN,
    command                 JSONB,
    log_summary             TEXT,
    image                   VARCHAR(256),
    limits                  JSONB,
    network_mode            VARCHAR(16),

    test_cases              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    evidence_schema_version SMALLINT     NOT NULL DEFAULT 1,

    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT replay_attempt_side_chk
        CHECK (side IN ('PRIMARY', 'FIXED')),
    CONSTRAINT replay_attempt_ordinal_chk
        CHECK (attempt_ordinal IN (1, 2)),
    CONSTRAINT replay_attempt_round_chk
        CHECK (replay_round >= 0),
    CONSTRAINT replay_attempt_phase_chk
        CHECK (phase IN ('PRE_EXECUTION_FAILURE', 'EXECUTED', 'REPORT_FAILURE')),
    CONSTRAINT replay_attempt_evidence_chk
        CHECK (target_evidence IN ('TARGET_PASSED', 'TARGET_ASSERTION_FAILURE', 'INVALID')),
    CONSTRAINT replay_attempt_schema_chk
        CHECK (evidence_schema_version = 1),
    CONSTRAINT replay_attempt_elapsed_chk
        CHECK (elapsed_ms IS NULL OR elapsed_ms >= 0),
    CONSTRAINT replay_attempt_log_size_chk
        CHECK (log_summary IS NULL OR octet_length(log_summary) <= 65536),
    CONSTRAINT replay_attempt_cases_array_chk
        CHECK (jsonb_typeof(test_cases) = 'array'),
    CONSTRAINT replay_attempt_cases_size_chk
        CHECK (octet_length(test_cases::text) <= 8388608),
    CONSTRAINT replay_attempt_command_size_chk
        CHECK (command IS NULL OR octet_length(command::text) <= 8192),
    CONSTRAINT replay_attempt_limits_size_chk
        CHECK (limits IS NULL OR octet_length(limits::text) <= 1024),
    CONSTRAINT replay_attempt_phase_shape_chk
        CHECK (
            (phase = 'PRE_EXECUTION_FAILURE'
                AND sandbox_status IS NULL
                AND exit_code IS NULL
                AND outcome IS NULL
                AND target_evidence = 'INVALID'
                AND diagnostic IS NOT NULL)
            OR (phase = 'EXECUTED'
                AND sandbox_status IS NOT NULL
                AND outcome IS NOT NULL
                AND diagnostic IS NULL)
            OR (phase = 'REPORT_FAILURE'
                AND sandbox_status IS NOT NULL
                AND outcome IS NOT NULL
                AND target_evidence = 'INVALID'
                AND diagnostic IS NOT NULL)
        ),
    CONSTRAINT replay_attempt_unique
        UNIQUE (run_id, replay_round, side, attempt_ordinal)
);

CREATE INDEX replay_attempt_run_round_idx
    ON replay_attempt (run_id, replay_round);
