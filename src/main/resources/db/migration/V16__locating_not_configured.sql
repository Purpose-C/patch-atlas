-- 配置缺失与定位零命中分开。只追加 LOCATING_NOT_CONFIGURED，已有零命中行语义不变。

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
                'LOCATING_NOT_CONFIGURED'
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
                        'LOCATING_NOT_CONFIGURED'))
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
