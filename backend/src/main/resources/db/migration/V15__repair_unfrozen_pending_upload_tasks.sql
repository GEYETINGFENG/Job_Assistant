-- 旧版本可能产生没有 processing 对象的 PENDING 任务，退回 UPLOADING 后可由客户端重新确认。
UPDATE resume_upload_session
SET status = 'UPLOADING',
    next_attempt_at = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE status = 'PENDING'
  AND processing_object_key IS NULL;
