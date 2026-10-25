-- 第 8 课 成本可观测：记录简历解析调用 LLM 消耗的 token，按上传任务（即按用户）归属成本。
-- 本地解析（AI 关闭）或命中解析缓存时没有调用 LLM，两列保持 NULL。

ALTER TABLE resume_upload_session
    ADD COLUMN prompt_tokens INTEGER,
    ADD COLUMN completion_tokens INTEGER;

ALTER TABLE resume_upload_session
    ADD CONSTRAINT ck_resume_upload_session_tokens
        CHECK ((prompt_tokens IS NULL OR prompt_tokens >= 0) AND (completion_tokens IS NULL OR completion_tokens >= 0));

COMMENT ON COLUMN resume_upload_session.prompt_tokens IS 'LLM prompt tokens used to parse this upload, for cost attribution';
COMMENT ON COLUMN resume_upload_session.completion_tokens IS 'LLM completion tokens used to parse this upload, for cost attribution';
