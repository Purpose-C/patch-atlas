-- 文本工具轨迹：放宽 step_kind、增加 outcome、context_origin 增加 TEXT_TOOLS、
-- input_schema_version 允许 3。不改写已有行的 schema 版本。

ALTER TABLE locating_trace
    DROP CONSTRAINT locating_trace_kind_chk,
    ADD CONSTRAINT locating_trace_kind_chk
        CHECK (step_kind IN (
            'SELECTION', 'EXCLUSION', 'SEARCH', 'LIST', 'READ', 'SUBMIT', 'BUDGET_EXHAUSTED'
        ));

ALTER TABLE locating_trace
    ADD COLUMN outcome VARCHAR(16) NOT NULL DEFAULT 'OK';

ALTER TABLE locating_trace
    ADD CONSTRAINT locating_trace_outcome_chk
        CHECK (outcome IN ('OK', 'ERROR'));

UPDATE locating_trace SET outcome = 'OK' WHERE outcome IS NULL;

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_context_origin_chk,
    ADD CONSTRAINT verification_run_context_origin_chk
        CHECK (
            context_origin IS NULL
            OR context_origin IN ('PINNED', 'HEURISTIC', 'TEXT_TOOLS')
        );

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_input_schema_chk,
    ADD CONSTRAINT verification_run_input_schema_chk
        CHECK (input_schema_version IN (1, 2, 3));
