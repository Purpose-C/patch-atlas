-- 定位阶段模型用量单独成账，不计入 generation 的 usage 计数。

ALTER TABLE verification_run
    ADD COLUMN locating_model_calls         SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN locating_usage_record_count  SMALLINT NULL DEFAULT 0,
    ADD COLUMN locating_input_tokens        BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN locating_output_tokens       BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN locating_total_tokens        BIGINT NOT NULL DEFAULT 0;

ALTER TABLE verification_run
    ADD CONSTRAINT verification_run_locating_usage_chk
        CHECK (
            locating_model_calls >= 0
            AND locating_input_tokens >= 0
            AND locating_output_tokens >= 0
            AND locating_total_tokens >= 0
            AND (
                locating_usage_record_count IS NULL
                OR (
                    locating_usage_record_count >= 0
                    AND locating_usage_record_count <= locating_model_calls
                )
            )
        );
