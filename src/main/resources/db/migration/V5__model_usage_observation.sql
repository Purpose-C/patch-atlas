-- Nullable usage record count. Do not rewrite V1–V4.

ALTER TABLE verification_run
    ADD COLUMN model_usage_record_count SMALLINT NULL DEFAULT 0;

-- Legacy attempted runs cannot reconstruct how many usage records existed.
UPDATE verification_run
   SET model_usage_record_count = NULL
 WHERE generation_attempt_count > 0;

ALTER TABLE verification_run
    ADD CONSTRAINT verification_run_model_usage_record_count_chk
        CHECK (
            model_usage_record_count IS NULL
            OR (
                model_usage_record_count >= 0
                AND model_usage_record_count <= generation_attempt_count
            )
        );
