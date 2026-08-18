-- 图工具定位：放宽 context_origin、步骤类型，并追加协议类失败。
-- 只追加 GRAPH_TOOLS / FIND / EXPAND / LOCATING_TOOL_PROTOCOL_ERROR，不改已有行。

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_context_origin_chk,
    ADD CONSTRAINT verification_run_context_origin_chk
        CHECK (
            context_origin IS NULL
            OR context_origin IN ('PINNED', 'HEURISTIC', 'TEXT_TOOLS', 'GRAPH_TOOLS')
        );

ALTER TABLE locating_trace
    DROP CONSTRAINT locating_trace_kind_chk,
    ADD CONSTRAINT locating_trace_kind_chk
        CHECK (step_kind IN (
            'SELECTION', 'EXCLUSION', 'SEARCH', 'LIST', 'READ', 'SUBMIT',
            'FIND', 'EXPAND',
            'BUDGET_EXHAUSTED', 'BUDGET_WARNING', 'UNKNOWN_TOOL'
        ));

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_failure_category_chk,
    ADD CONSTRAINT verification_run_failure_category_chk
        CHECK (
            failure_category IS NULL
            OR failure_category IN (
                'GENERATION_FAILURE',
                'GENERATION_EXHAUSTED',
                'MODEL_CONFIGURATION_ERROR',
                'MODEL_AUTHENTICATION_ERROR',
                'MODEL_UNAVAILABLE',
                'MODEL_REFUSED',
                'PATCH_REJECTED',
                'WORKSPACE_UNSAFE',
                'WORKSPACE_ERROR',
                'REPLAY_SYSTEM_ERROR',
                'RECOVERY_EXHAUSTED',
                'LOCATING_NO_CONTEXT',
                'LOCATING_NOT_CONFIGURED',
                'LOCATING_TOOL_PROTOCOL_ERROR'
            )
        );

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_failure_pair_chk,
    ADD CONSTRAINT verification_run_failure_pair_chk
        CHECK (
            failure_stage IS NULL
            OR (
                (failure_stage = 'LOCATING'
                    AND failure_category IN (
                        'LOCATING_NO_CONTEXT',
                        'LOCATING_NOT_CONFIGURED',
                        'LOCATING_TOOL_PROTOCOL_ERROR'))
                OR (failure_stage = 'GENERATION'
                    AND failure_category IN (
                        'GENERATION_FAILURE',
                        'GENERATION_EXHAUSTED',
                        'MODEL_CONFIGURATION_ERROR',
                        'MODEL_AUTHENTICATION_ERROR',
                        'MODEL_UNAVAILABLE',
                        'MODEL_REFUSED'))
                OR (failure_stage = 'PATCH_GATE'
                    AND failure_category IN ('PATCH_REJECTED', 'WORKSPACE_UNSAFE'))
                OR (failure_stage = 'WORKSPACE'
                    AND failure_category IN ('WORKSPACE_UNSAFE', 'WORKSPACE_ERROR'))
                OR (failure_stage = 'REPLAY'
                    AND failure_category = 'REPLAY_SYSTEM_ERROR')
                OR (failure_stage = 'RECOVERY'
                    AND failure_category = 'RECOVERY_EXHAUSTED')
            )
        );
