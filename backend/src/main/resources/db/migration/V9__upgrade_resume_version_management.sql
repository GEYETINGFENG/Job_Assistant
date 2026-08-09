-- 简历版本管理升级
-- 1. resume 表增加当前最新版本号
ALTER TABLE resume
    ADD COLUMN latest_version_number INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN resume.latest_version_number
IS 'Current latest resume version number';

ALTER TABLE resume
    ADD CONSTRAINT ck_resume_latest_version_number
        CHECK (latest_version_number >= 0);

-- 2. 为没有历史版本的旧简历补充 V1
INSERT INTO resume_version
(
    resume_id,
    version_number,
    content_json,
    create_time
)
SELECT
    resume.id,
    1,
    resume.parsed_json,
    COALESCE(resume.create_time, CURRENT_TIMESTAMP)
FROM resume
WHERE NOT EXISTS
          (
              SELECT 1
              FROM resume_version
              WHERE resume_version.resume_id = resume.id
          );

-- 3. 根据现有历史版本回填最新版本号
UPDATE resume
SET latest_version_number =
        (
            SELECT COALESCE(MAX(resume_version.version_number), 0)
            FROM resume_version
            WHERE resume_version.resume_id = resume.id
        );


-- 4. 上传会话增加上传类型和版本号
ALTER TABLE resume_upload_session
    ADD COLUMN upload_type VARCHAR(20) NOT NULL DEFAULT 'CREATE';

ALTER TABLE resume_upload_session
    ADD COLUMN version_number INTEGER;

COMMENT ON COLUMN resume_upload_session.upload_type
IS 'Upload type: CREATE or NEW_VERSION';

COMMENT ON COLUMN resume_upload_session.version_number
IS 'Version number created by this upload session';

ALTER TABLE resume_upload_session
    ADD CONSTRAINT ck_resume_upload_session_upload_type
        CHECK (upload_type IN ('CREATE', 'NEW_VERSION'));

ALTER TABLE resume_upload_session
    ADD CONSTRAINT ck_resume_upload_session_version_number
        CHECK (version_number IS NULL OR version_number > 0);


-- 5. 为旧的已完成 S3 上传记录回填版本号

UPDATE resume_upload_session
SET version_number = resume.latest_version_number
    FROM resume
WHERE resume_upload_session.resume_id = resume.id
  AND resume_upload_session.status = 'COMPLETED'
  AND resume_upload_session.version_number IS NULL;


-- 6. 删除一份简历只能有一个上传会话的唯一索引

DROP INDEX IF EXISTS uk_resume_upload_session_resume_id;


-- 7. 创建新的普通查询索引

CREATE INDEX IF NOT EXISTS idx_resume_upload_session_resume_id
    ON resume_upload_session(resume_id);


CREATE INDEX IF NOT EXISTS idx_resume_upload_session_resume_version
    ON resume_upload_session(resume_id, version_number);


-- 8. 一份简历的某个 S3 版本只能有一条已完成上传记录

CREATE UNIQUE INDEX uk_resume_upload_session_completed_version
    ON resume_upload_session(resume_id, version_number)
    WHERE resume_id IS NOT NULL
  AND version_number IS NOT NULL
  AND status = 'COMPLETED';