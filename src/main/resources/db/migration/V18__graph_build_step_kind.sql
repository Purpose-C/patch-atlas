-- 建图事件单独分类，不再并进启发式 SELECTION。只追加 GRAPH_BUILD，不改已有行。

ALTER TABLE locating_trace
    DROP CONSTRAINT locating_trace_kind_chk,
    ADD CONSTRAINT locating_trace_kind_chk
        CHECK (step_kind IN (
            'SELECTION', 'EXCLUSION', 'SEARCH', 'LIST', 'READ', 'SUBMIT',
            'FIND', 'EXPAND', 'GRAPH_BUILD',
            'BUDGET_EXHAUSTED', 'BUDGET_WARNING', 'UNKNOWN_TOOL'
        ));
