-- 将 resume_upload_session 扩展为持久化任务表，为后续数据库 worker 做准备。

ALTER TABLE resume_upload_session
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN processing_started_at TIMESTAMPTZ,
    ADD COLUMN claim_token UUID,
    ADD COLUMN last_error_code VARCHAR(64),
    ADD COLUMN last_error_message VARCHAR(512),
    ADD COLUMN upload_object_key VARCHAR(512),
    ADD COLUMN processing_object_key VARCHAR(512),
    ADD COLUMN final_object_key VARCHAR(512);

-- 旧 PENDING 表示“等待客户端上传”，新状态机使用 UPLOADING 表达该阶段。
UPDATE resume_upload_session
SET status = 'UPLOADING'
WHERE status = 'PENDING';

-- 部署时旧进程已经停止；遗留 PROCESSING 任务回到待处理队列，避免永久卡住。
UPDATE resume_upload_session
SET status = 'PENDING',
    next_attempt_at = CURRENT_TIMESTAMP
WHERE status = 'PROCESSING';

-- 旧 FAILED 没有自动重试能力，迁移为死信任务，保留给人工检查。
UPDATE resume_upload_session
SET status = 'DEAD'
WHERE status = 'FAILED';

-- 旧 object_key 在完成前表示上传对象，完成后表示最终对象；按现有状态拆分其含义。
UPDATE resume_upload_session
SET final_object_key = object_key
WHERE status = 'COMPLETED';

UPDATE resume_upload_session
SET upload_object_key = object_key
WHERE status <> 'COMPLETED';

ALTER TABLE resume_upload_session
    DROP COLUMN object_key;

ALTER TABLE resume_upload_session
    ADD CONSTRAINT ck_resume_upload_session_status
        CHECK (status IN ('UPLOADING', 'PENDING', 'PROCESSING', 'COMPLETED', 'DEAD', 'EXPIRED')),
    ADD CONSTRAINT ck_resume_upload_session_attempt_count
        CHECK (attempt_count >= 0);

COMMENT ON COLUMN resume_upload_session.attempt_count
IS 'Number of processing attempts already claimed by workers';

COMMENT ON COLUMN resume_upload_session.next_attempt_at
IS 'Earliest time at which a PENDING task may be claimed';

COMMENT ON COLUMN resume_upload_session.processing_started_at
IS 'Time at which the current worker claimed the task';

COMMENT ON COLUMN resume_upload_session.claim_token
IS 'Unique token for the current processing attempt';

COMMENT ON COLUMN resume_upload_session.last_error_code
IS 'Stable error code from the latest failed processing attempt';

COMMENT ON COLUMN resume_upload_session.last_error_message
IS 'Sanitized error message from the latest failed processing attempt';

COMMENT ON COLUMN resume_upload_session.upload_object_key
IS 'Client-writable S3 object created through the presigned upload URL';

COMMENT ON COLUMN resume_upload_session.processing_object_key
IS 'Immutable S3 object used by the background processor';

COMMENT ON COLUMN resume_upload_session.final_object_key
IS 'Validated S3 object retained for completed resume downloads';

CREATE UNIQUE INDEX uk_resume_upload_session_upload_object_key
    ON resume_upload_session(upload_object_key)
    WHERE upload_object_key IS NOT NULL;

CREATE UNIQUE INDEX uk_resume_upload_session_processing_object_key
    ON resume_upload_session(processing_object_key)
    WHERE processing_object_key IS NOT NULL;

CREATE UNIQUE INDEX uk_resume_upload_session_final_object_key
    ON resume_upload_session(final_object_key)
    WHERE final_object_key IS NOT NULL;

CREATE INDEX idx_resume_upload_session_pending_queue
    ON resume_upload_session(next_attempt_at, create_time)
    WHERE status = 'PENDING';

CREATE INDEX idx_resume_upload_session_processing_timeout
    ON resume_upload_session(processing_started_at)
    WHERE status = 'PROCESSING';

-- CREATE 上传后续也会接收 Idempotency-Key；先建立数据库最终兜底约束。
CREATE UNIQUE INDEX uk_resume_upload_session_create_idempotency
    ON resume_upload_session(user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL
      AND upload_type = 'CREATE';
