-- Generation attempt budget + model usage on verification_run only.

ALTER TABLE verification_run
    ADD COLUMN generation_attempt_count SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN model_provider           VARCHAR(32),
    ADD COLUMN model_name               VARCHAR(128),
    ADD COLUMN model_input_tokens       BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN model_output_tokens      BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN model_total_tokens       BIGINT NOT NULL DEFAULT 0;

ALTER TABLE verification_run
    ADD CONSTRAINT verification_run_generation_attempt_chk
        CHECK (generation_attempt_count >= 0 AND generation_attempt_count <= 3),
    ADD CONSTRAINT verification_run_model_tokens_chk
        CHECK (
            model_input_tokens >= 0
            AND model_output_tokens >= 0
            AND model_total_tokens >= 0
        ),
    ADD CONSTRAINT verification_run_model_identity_chk
        CHECK (
            (generation_attempt_count = 0
                AND model_provider IS NULL
                AND model_name IS NULL)
            OR (generation_attempt_count > 0
                AND model_provider IN ('fake', 'openai')
                AND model_name IS NOT NULL
                AND char_length(model_name) BETWEEN 1 AND 128)
        ),
    ADD CONSTRAINT verification_run_failure_stage_chk
        CHECK (
            failure_stage IS NULL
            OR failure_stage IN ('GENERATION', 'PATCH_GATE', 'WORKSPACE', 'REPLAY', 'RECOVERY')
        ),
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
                'REPLAY_SYSTEM_ERROR',
                'RECOVERY_EXHAUSTED'
            )
        ),
    ADD CONSTRAINT verification_run_failure_pair_chk
        CHECK (
            failure_stage IS NULL
            OR (
                (failure_stage = 'GENERATION'
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
                    AND failure_category = 'WORKSPACE_UNSAFE')
                OR (failure_stage = 'REPLAY'
                    AND failure_category = 'REPLAY_SYSTEM_ERROR')
                OR (failure_stage = 'RECOVERY'
                    AND failure_category = 'RECOVERY_EXHAUSTED')
            )
        );
