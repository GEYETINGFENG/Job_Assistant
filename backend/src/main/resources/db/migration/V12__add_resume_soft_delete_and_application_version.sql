-- 1. Resume 增加软删除字段

ALTER TABLE resume
    ADD COLUMN is_delete INTEGER NOT NULL DEFAULT 0;

ALTER TABLE resume
    ADD COLUMN delete_time TIMESTAMP;

COMMENT ON COLUMN resume.is_delete
IS 'Soft delete flag: 0 = active, 1 = deleted';

COMMENT ON COLUMN resume.delete_time
IS 'Time when the resume was soft deleted';

ALTER TABLE resume
    ADD CONSTRAINT ck_resume_is_delete
        CHECK (is_delete IN (0, 1));


-- 查询某个用户当前未删除的简历时使用。
CREATE INDEX idx_resume_user_active
    ON resume(user_id)
    WHERE is_delete = 0;


-- 2. Resume -> ResumeVersion
-- 原来使用 ON DELETE CASCADE：删除 Resume 会把所有 ResumeVersion 一起删除。
-- 现在改成 RESTRICT：只要 ResumeVersion 还存在，就禁止物理删除 Resume。

ALTER TABLE resume_version
DROP CONSTRAINT IF EXISTS fk_version_resume;

ALTER TABLE resume_version
    ADD CONSTRAINT fk_version_resume
        FOREIGN KEY(resume_id)
            REFERENCES resume(id)
            ON DELETE RESTRICT;


-- 3. Application 增加 resume_version_id

ALTER TABLE application
    ADD COLUMN resume_version_id BIGINT;

COMMENT ON COLUMN application.resume_version_id
IS 'Resume version used when this application was created';


-- 4. Application -> ResumeVersion 使用 RESTRICT
-- 只要某个 ResumeVersion 正被 Application 引用，就禁止物理删除这个 ResumeVersion。

ALTER TABLE application
    ADD CONSTRAINT fk_application_resume_version
        FOREIGN KEY(resume_version_id)
            REFERENCES resume_version(id)
            ON DELETE RESTRICT;


CREATE INDEX idx_application_resume_version ON application(resume_version_id);

-- Application 是历史记录。
-- Job 被物理删除时，不应该把 Application 一起 CASCADE 删除。

ALTER TABLE application
DROP CONSTRAINT IF EXISTS fk_application_job;

ALTER TABLE application
    ADD CONSTRAINT fk_application_job
        FOREIGN KEY(job_id)
            REFERENCES job(id)
            ON DELETE RESTRICT;