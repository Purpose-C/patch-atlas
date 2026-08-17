-- 预算将尽提示。只追加 BUDGET_WARNING，不改已有行。

ALTER TABLE locating_trace
    DROP CONSTRAINT locating_trace_kind_chk,
    ADD CONSTRAINT locating_trace_kind_chk
        CHECK (step_kind IN (
            'SELECTION', 'EXCLUSION', 'SEARCH', 'LIST', 'READ', 'SUBMIT',
            'BUDGET_EXHAUSTED', 'BUDGET_WARNING'
        ));
