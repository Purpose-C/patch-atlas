-- Persist per-run generation finish_reason. Missing evidence is the token
-- unknown, not NULL.

ALTER TABLE verification_run
    ADD COLUMN model_finish_reason VARCHAR(32) NOT NULL DEFAULT 'unknown';

ALTER TABLE verification_run
    ADD CONSTRAINT verification_run_model_finish_reason_chk
        CHECK (model_finish_reason ~ '^[a-z0-9_-]{1,32}$');
