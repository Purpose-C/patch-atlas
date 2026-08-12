-- Idempotency-Key + normalized submission fingerprint on verification_run.

ALTER TABLE verification_run
    ADD COLUMN idempotency_key   VARCHAR(128),
    ADD COLUMN submission_sha256 CHAR(64);

ALTER TABLE verification_run
    ADD CONSTRAINT verification_run_idempotency_pair_chk
        CHECK (
            (idempotency_key IS NULL AND submission_sha256 IS NULL)
            OR (idempotency_key IS NOT NULL AND submission_sha256 IS NOT NULL)
        ),
    ADD CONSTRAINT verification_run_idempotency_key_len_chk
        CHECK (
            idempotency_key IS NULL
            OR (char_length(idempotency_key) BETWEEN 1 AND 128
                AND idempotency_key ~ '^[A-Za-z0-9._:-]+$')
        ),
    ADD CONSTRAINT verification_run_submission_sha256_chk
        CHECK (
            submission_sha256 IS NULL
            OR submission_sha256 ~ '^[0-9a-f]{64}$'
        );

CREATE UNIQUE INDEX verification_run_idempotency_key_uidx
    ON verification_run (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
