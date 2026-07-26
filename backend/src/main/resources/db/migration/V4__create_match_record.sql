CREATE TABLE match_record
(
    id BIGSERIAL PRIMARY KEY,


    resume_id BIGINT NOT NULL,


    job_id BIGINT NOT NULL,


    score INTEGER,


    analysis_json JSONB,


    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,


    CONSTRAINT fk_match_resume
    FOREIGN KEY(resume_id)
    REFERENCES resume(id)
    ON DELETE CASCADE,


    CONSTRAINT fk_match_job
    FOREIGN KEY(job_id)
    REFERENCES job(id)
    ON DELETE CASCADE
);



COMMENT ON TABLE match_record
IS 'Resume and job matching record';


COMMENT ON COLUMN match_record.analysis_json
IS 'AI matching analysis result';



-- 查询某份简历匹配记录
CREATE INDEX idx_match_resume
ON match_record(resume_id);



-- 查询某岗位匹配记录
CREATE INDEX idx_match_job
ON match_record(job_id);



-- 按分数排序
CREATE INDEX idx_match_score
ON match_record(score DESC);



-- JSON分析结果索引
CREATE INDEX idx_match_analysis_json
ON match_record
USING GIN(analysis_json);