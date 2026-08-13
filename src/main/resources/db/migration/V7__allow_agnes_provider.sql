-- 允许 agnes 作为合法 model provider（经 OpenAI 兼容端点调用）。
-- PostgreSQL 不能原地改 CHECK，需 DROP + ADD；其余条件逐字保留。

ALTER TABLE verification_run
    DROP CONSTRAINT verification_run_model_identity_chk,
    ADD CONSTRAINT verification_run_model_identity_chk
        CHECK (
            (generation_attempt_count = 0
                AND model_provider IS NULL
                AND model_name IS NULL)
            OR (generation_attempt_count > 0
                AND model_provider IN ('fake', 'openai', 'agnes')
                AND model_name IS NOT NULL
                AND char_length(model_name) BETWEEN 1 AND 128)
        );