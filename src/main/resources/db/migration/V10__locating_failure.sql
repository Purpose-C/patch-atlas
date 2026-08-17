-- 定位失败与生成失败分开：空启发式选择不得进入 GENERATING。

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_failure_stage_chk,
    ADD CONSTRAINT verification_run_failure_stage_chk
        CHECK (
            failure_stage IS NULL
            OR failure_stage IN (
                'LOCATING', 'GENERATION', 'PATCH_GATE', 'WORKSPACE', 'REPLAY', 'RECOVERY'
            )
        );

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
                'LOCATING_NO_CONTEXT'
            )
        );

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_failure_pair_chk,
    ADD CONSTRAINT verification_run_failure_pair_chk
        CHECK (
            failure_stage IS NULL
            OR (
                (failure_stage = 'LOCATING'
                    AND failure_category = 'LOCATING_NO_CONTEXT')
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
