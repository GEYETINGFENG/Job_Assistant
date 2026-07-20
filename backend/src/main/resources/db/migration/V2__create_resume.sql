CREATE TABLE resume
(
    id BIGSERIAL PRIMARY KEY,


    user_id BIGINT NOT NULL,


    resume_name VARCHAR(256),


    file_url VARCHAR(1024),


    parsed_json JSONB,


    status INTEGER DEFAULT 0,


    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,


    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,


    CONSTRAINT fk_resume_user
    FOREIGN KEY(user_id)
    REFERENCES users(id)
    ON DELETE CASCADE
);



COMMENT ON TABLE resume
IS 'User resume table';


COMMENT ON COLUMN resume.parsed_json
IS 'AI parsed resume JSON data';



-- 查询某个用户所有简历
CREATE INDEX idx_resume_user_id
ON resume(user_id);



-- 简历状态查询
CREATE INDEX idx_resume_status
ON resume(status);



-- JSONB字段GIN索引
CREATE INDEX idx_resume_json
ON resume
USING GIN(parsed_json);