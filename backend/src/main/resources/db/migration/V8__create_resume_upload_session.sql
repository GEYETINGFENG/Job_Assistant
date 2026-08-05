CREATE TABLE resume_upload_session (
    id UUID PRIMARY KEY,
    -- 上传会话所属用户
    user_id BIGINT NOT NULL,
    resume_name VARCHAR(256) NOT NULL,
    original_filename VARCHAR(256) NOT NULL,
    -- S3 对象 Key，只能由后端生成
    object_key VARCHAR(512) NOT NULL UNIQUE,
    expected_extension VARCHAR(10) NOT NULL,
    expected_content_type VARCHAR(128) NOT NULL,
    expected_size BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    -- 上传完成后关联创建出来的简历
    resume_id BIGINT,
    create_time TIMESTAMPTZ NOT NULL,
    update_time TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_resume_upload_session_user
        FOREIGN KEY (user_id)
            REFERENCES users(id),

    CONSTRAINT fk_resume_upload_session_resume
        FOREIGN KEY (resume_id)
            REFERENCES resume(id)
);

CREATE INDEX idx_resume_upload_session_user_id ON resume_upload_session(user_id);

CREATE UNIQUE INDEX uk_resume_upload_session_resume_id ON resume_upload_session(resume_id) WHERE resume_id IS NOT NULL;