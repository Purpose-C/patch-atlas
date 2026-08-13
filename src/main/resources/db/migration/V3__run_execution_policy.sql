-- Network mode is an immutable Verification Run input.

ALTER TABLE verification_run
    ADD COLUMN network_mode VARCHAR(16) NOT NULL DEFAULT 'OFFLINE';

UPDATE verification_run
   SET java_version = '21'
 WHERE java_version IS NULL;

-- V1 接受自由文本版本；保留受支持的 Java 主版本并规范化常见的补丁版本写法。
UPDATE verification_run
   SET java_version = split_part(java_version, '.', 1)
 WHERE split_part(java_version, '.', 1) IN ('8', '11', '17', '21');

ALTER TABLE verification_run
    ALTER COLUMN java_version SET DEFAULT '21',
    ALTER COLUMN java_version SET NOT NULL;

ALTER TABLE verification_run
    ADD CONSTRAINT verification_run_java_version_chk
        CHECK (java_version IN ('8', '11', '17', '21')),
    ADD CONSTRAINT verification_run_network_mode_chk
        CHECK (network_mode IN ('OFFLINE', 'ONLINE'));
