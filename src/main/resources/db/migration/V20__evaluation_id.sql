-- Benchmark batch discriminator. Not an evidence-report field; case_id stays
-- the public case identity.

ALTER TABLE verification_run
    ADD COLUMN evaluation_id VARCHAR(64);

ALTER TABLE verification_run
    ADD CONSTRAINT verification_run_evaluation_id_chk
        CHECK (evaluation_id IS NULL OR evaluation_id ~ '^[a-z0-9-]{1,64}$');

CREATE INDEX verification_run_evaluation_lookup_idx
    ON verification_run (case_id, run_purpose, context_origin, evaluation_id);
