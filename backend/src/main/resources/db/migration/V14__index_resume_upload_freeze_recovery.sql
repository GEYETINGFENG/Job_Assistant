-- 加速查找“处理对象已经冻结，但任务尚未进入 PENDING”的崩溃恢复候选。
CREATE INDEX idx_resume_upload_session_freeze_recovery
    ON resume_upload_session(update_time)
    WHERE status = 'UPLOADING'
      AND processing_object_key IS NOT NULL;
