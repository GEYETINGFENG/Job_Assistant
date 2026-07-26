CREATE TABLE resume_version
(
    id BIGSERIAL PRIMARY KEY,


    resume_id BIGINT NOT NULL,


    version_number INTEGER NOT NULL,


    content_json JSONB,


    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,


    CONSTRAINT fk_version_resume
    FOREIGN KEY(resume_id)
    REFERENCES resume(id)
    ON DELETE CASCADE
);



COMMENT ON TABLE resume_version
IS 'Resume version history';



COMMENT ON COLUMN resume_version.content_json
IS 'Historical resume JSON content';



-- 查询某份简历版本
CREATE INDEX idx_version_resume
ON resume_version(resume_id);



-- JSONB索引
CREATE INDEX idx_version_json
ON resume_version
USING GIN(content_json);



-- 同一简历版本号唯一
CREATE UNIQUE INDEX uk_resume_version
ON resume_version(resume_id, version_number);