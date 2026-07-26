CREATE TABLE application
(
    id BIGSERIAL PRIMARY KEY,


    user_id BIGINT NOT NULL,


    job_id BIGINT NOT NULL,


    status INTEGER DEFAULT 0,


    apply_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,


    CONSTRAINT fk_application_user
    FOREIGN KEY(user_id)
    REFERENCES users(id)
    ON DELETE CASCADE,


    CONSTRAINT fk_application_job
    FOREIGN KEY(job_id)
    REFERENCES job(id)
    ON DELETE CASCADE
);



COMMENT ON TABLE application
IS 'Job application record';



-- 查询用户投递记录
CREATE INDEX idx_application_user
ON application(user_id);



-- 查询岗位投递记录
CREATE INDEX idx_application_job
ON application(job_id);



-- 状态筛选
CREATE INDEX idx_application_status
ON application(status);



-- 防止重复投递
CREATE UNIQUE INDEX uk_application_user_job
ON application(user_id, job_id);