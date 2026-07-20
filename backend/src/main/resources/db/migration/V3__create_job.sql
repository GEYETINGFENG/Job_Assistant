CREATE TABLE job
(
    id BIGSERIAL PRIMARY KEY,


    company_name VARCHAR(256) NOT NULL,


    job_title VARCHAR(256) NOT NULL,


    description TEXT,


    requirements JSONB,


    location VARCHAR(256),


    salary VARCHAR(128),


    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,


    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);



COMMENT ON TABLE job
IS 'Job information table';


COMMENT ON COLUMN job.requirements
IS 'Job requirements JSON data';



-- 公司查询
CREATE INDEX idx_job_company
ON job(company_name);



-- 岗位名称查询
CREATE INDEX idx_job_title
ON job(job_title);



-- 地区查询
CREATE INDEX idx_job_location
ON job(location);



-- JSONB岗位要求索引
CREATE INDEX idx_job_requirements_json
ON job
USING GIN(requirements);