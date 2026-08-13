-- Make benchmark purpose and test-patch provenance explicit.

ALTER TABLE verification_run
    ADD COLUMN run_purpose VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    ADD CONSTRAINT verification_run_purpose_chk
        CHECK (run_purpose IN ('STANDARD', 'CALIBRATION', 'AGENT_BENCHMARK', 'DIAGNOSTIC'));

ALTER TABLE candidate_test_patch
    ADD COLUMN patch_provenance VARCHAR(32) NOT NULL DEFAULT 'AGENT_GENERATED',
    ADD CONSTRAINT candidate_test_patch_provenance_chk
        CHECK (patch_provenance IN ('AGENT_GENERATED', 'KNOWN_TRIGGER'));
