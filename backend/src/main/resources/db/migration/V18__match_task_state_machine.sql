-- 匹配异步链路：match_record 从"只存结果"改造成持久化任务表（与 V13 上传任务相同的状态机思路）。
-- POST /matches 只落一条 PENDING 记录并返回 202；worker 用 FOR UPDATE SKIP LOCKED 领取后调用 LLM。

-- 1. 新增列（先允许为空，回填旧数据后再加 NOT NULL）
ALTER TABLE match_record
    ADD COLUMN user_id BIGINT,
    ADD COLUMN resume_version_id BIGINT,
    ADD COLUMN status VARCHAR(16),
    ADD COLUMN engine VARCHAR(16),
    ADD COLUMN model_version VARCHAR(128),
    ADD COLUMN prompt_version VARCHAR(64),
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN processing_started_at TIMESTAMPTZ,
    ADD COLUMN claim_token UUID,
    ADD COLUMN last_error_code VARCHAR(64),
    ADD COLUMN last_error_message VARCHAR(512),
    ADD COLUMN prompt_tokens INTEGER,
    ADD COLUMN completion_tokens INTEGER,
    ADD COLUMN update_time TIMESTAMP;

-- 2. 旧数据回填：旧记录只关联 resume，匹配对象改为具体版本，取该简历当时的最新版本。
UPDATE match_record AS m
SET user_id = r.user_id,
    resume_version_id = (
        SELECT rv.id
        FROM resume_version rv
        WHERE rv.resume_id = m.resume_id
        ORDER BY rv.version_number DESC
        LIMIT 1
    )
FROM resume AS r
WHERE r.id = m.resume_id;

-- 无法确定归属或版本的旧记录没有意义，直接删除。
DELETE FROM match_record
WHERE user_id IS NULL OR resume_version_id IS NULL;

-- 有分数和分析结果的视为已完成；分数越界或结果不完整的标为 DEAD，用户重新发起即可。
UPDATE match_record
SET engine = 'LLM',
    prompt_version = 'legacy',
    create_time = COALESCE(create_time, CURRENT_TIMESTAMP),
    update_time = COALESCE(create_time, CURRENT_TIMESTAMP),
    status = CASE
        WHEN score BETWEEN 0 AND 100 AND analysis_json IS NOT NULL THEN 'COMPLETED'
        ELSE 'DEAD'
    END;

UPDATE match_record
SET score = NULL,
    last_error_code = 'LEGACY_INCOMPLETE',
    last_error_message = 'Migrated record without a valid result'
WHERE status = 'DEAD';

-- 同一组合只保留一条，后面才能建唯一索引：优先保留已完成的结果，其次保留最新的。
DELETE FROM match_record
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY resume_version_id, job_id, engine, prompt_version
                   ORDER BY (status = 'COMPLETED') DESC, id DESC
               ) AS rank_in_group
        FROM match_record
    ) ranked
    WHERE rank_in_group > 1
);

-- 3. 约束
ALTER TABLE match_record
    ALTER COLUMN user_id SET NOT NULL,
    ALTER COLUMN resume_version_id SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN status SET DEFAULT 'PENDING',
    ALTER COLUMN engine SET NOT NULL,
    ALTER COLUMN prompt_version SET NOT NULL,
    ALTER COLUMN create_time SET NOT NULL,
    ALTER COLUMN update_time SET NOT NULL,
    ALTER COLUMN update_time SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE match_record
    ADD CONSTRAINT fk_match_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    -- 有匹配记录引用的版本不能被物理删除（和 application 一致，简历只做软删除）。
    ADD CONSTRAINT fk_match_resume_version
        FOREIGN KEY (resume_version_id) REFERENCES resume_version(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_match_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD')),
    ADD CONSTRAINT ck_match_engine
        CHECK (engine IN ('LLM', 'KEYWORD')),
    ADD CONSTRAINT ck_match_score
        CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    -- 完成的任务必须有结果，防止半条脏数据。
    ADD CONSTRAINT ck_match_completed_has_result
        CHECK (status <> 'COMPLETED' OR (score IS NOT NULL AND analysis_json IS NOT NULL)),
    ADD CONSTRAINT ck_match_attempt_count
        CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_match_tokens
        CHECK ((prompt_tokens IS NULL OR prompt_tokens >= 0) AND (completion_tokens IS NULL OR completion_tokens >= 0));

-- 幂等：同一版本简历 + 同一 JD + 同一分析方式只分析一次，重复请求直接返回已有记录。
CREATE UNIQUE INDEX uk_match_version_job
    ON match_record (resume_version_id, job_id, engine, prompt_version);

-- worker 领取队列：只索引 PENDING 行，按到期时间顺序取。
CREATE INDEX idx_match_pending_queue
    ON match_record (next_attempt_at, id)
    WHERE status = 'PENDING';

-- 处理超时恢复：只索引 PROCESSING 行。
CREATE INDEX idx_match_processing_started
    ON match_record (processing_started_at)
    WHERE status = 'PROCESSING';

-- 用户查看某个 JD 下的匹配记录，游标分页 id DESC。
CREATE INDEX idx_match_user_job
    ON match_record (user_id, job_id, id DESC);

-- 全局按分数排序和对 analysis_json 的 GIN 索引没有对应查询，只增加写入成本。
DROP INDEX IF EXISTS idx_match_score;
DROP INDEX IF EXISTS idx_match_analysis_json;
-- 新的唯一索引前导列是 resume_version_id，按 resume_id 查询的需求已不存在。
DROP INDEX IF EXISTS idx_match_resume;

COMMENT ON COLUMN match_record.status IS 'Match task status: PENDING / PROCESSING / COMPLETED / DEAD';
COMMENT ON COLUMN match_record.engine IS 'LLM, or KEYWORD fallback when the LLM is disabled';
COMMENT ON COLUMN match_record.prompt_tokens IS 'LLM prompt tokens used by this analysis, for cost attribution';
COMMENT ON COLUMN match_record.completion_tokens IS 'LLM completion tokens used by this analysis, for cost attribution';


-- 4. job：游标分页索引，时间字段非空
UPDATE job
SET create_time = COALESCE(create_time, CURRENT_TIMESTAMP),
    update_time = COALESCE(update_time, create_time, CURRENT_TIMESTAMP);

ALTER TABLE job
    ALTER COLUMN create_time SET NOT NULL,
    ALTER COLUMN update_time SET NOT NULL;

-- WHERE user_id = ? AND id < ? ORDER BY id DESC LIMIT ?
CREATE INDEX idx_job_user_id_desc
    ON job (user_id, id DESC);

-- 新索引前导列同样是 user_id，可以替代旧的单列索引。
DROP INDEX IF EXISTS idx_job_user_id;
