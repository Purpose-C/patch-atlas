-- 定位阶段：状态白名单、持租约形状、input_schema_version 双版本、
-- context_origin / code_graph_key 列、locating_trace 表。
-- PostgreSQL 不能原地改 CHECK；不改写已有行的 input_schema_version。

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_state_chk,
    ADD CONSTRAINT verification_run_state_chk
        CHECK (state IN (
            'QUEUED', 'LOCATING', 'GENERATING', 'REPLAYING', 'COMPLETED', 'FAILED'
        ));

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_lease_shape_chk,
    ADD CONSTRAINT verification_run_lease_shape_chk
        CHECK (
            (state = 'QUEUED'
                AND lease_token IS NULL
                AND lease_owner IS NULL
                AND lease_expires_at IS NULL)
            OR (state IN ('LOCATING', 'GENERATING', 'REPLAYING')
                AND lease_token IS NOT NULL
                AND lease_owner IS NOT NULL
                AND lease_expires_at IS NOT NULL)
            OR (state IN ('COMPLETED', 'FAILED')
                AND lease_token IS NULL
                AND lease_owner IS NULL
                AND lease_expires_at IS NULL)
        );

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_input_schema_chk,
    ADD CONSTRAINT verification_run_input_schema_chk
        CHECK (input_schema_version IN (1, 2));

ALTER TABLE verification_run
    ADD COLUMN context_origin VARCHAR(16),
    ADD COLUMN code_graph_key TEXT;

ALTER TABLE verification_run
    ADD CONSTRAINT verification_run_context_origin_chk
        CHECK (
            context_origin IS NULL
            OR context_origin IN ('PINNED', 'HEURISTIC')
        );

DROP INDEX verification_run_claim_idx;
CREATE INDEX verification_run_claim_idx
    ON verification_run (created_at ASC, id ASC)
    WHERE state = 'QUEUED'
       OR (state IN ('LOCATING', 'GENERATING', 'REPLAYING'));

DROP INDEX verification_run_lease_expiry_idx;
CREATE INDEX verification_run_lease_expiry_idx
    ON verification_run (lease_expires_at)
    WHERE state IN ('LOCATING', 'GENERATING', 'REPLAYING');

CREATE TABLE locating_trace (
    id              UUID         PRIMARY KEY,
    run_id          UUID         NOT NULL
                    REFERENCES verification_run (id),
    seq             INTEGER      NOT NULL,
    step_kind       VARCHAR(32)  NOT NULL,
    subject         VARCHAR(1024) NOT NULL,
    reason          VARCHAR(64)  NOT NULL,
    detail          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT locating_trace_seq_chk    CHECK (seq >= 0),
    CONSTRAINT locating_trace_kind_chk   CHECK (step_kind IN ('SELECTION', 'EXCLUSION')),
    CONSTRAINT locating_trace_detail_chk CHECK (octet_length(detail::text) <= 8192)
);

CREATE UNIQUE INDEX locating_trace_run_seq_uidx ON locating_trace (run_id, seq);
