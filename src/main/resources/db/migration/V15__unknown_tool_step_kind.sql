-- 未知工具名单独分类，不再并进 SEARCH。只追加 UNKNOWN_TOOL，不改已有行。

ALTER TABLE locating_trace
    DROP CONSTRAINT locating_trace_kind_chk,
    ADD CONSTRAINT locating_trace_kind_chk
        CHECK (step_kind IN (
            'SELECTION', 'EXCLUSION', 'SEARCH', 'LIST', 'READ', 'SUBMIT',
            'BUDGET_EXHAUSTED', 'BUDGET_WARNING', 'UNKNOWN_TOOL'
        ));
