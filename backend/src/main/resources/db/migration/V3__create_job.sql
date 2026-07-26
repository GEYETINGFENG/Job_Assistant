CREATE TABLE job
(
    id BIGSERIAL PRIMARY KEY,


    -- Job owner.
    user_id BIGINT NOT NULL,


    company_name VARCHAR(256) NOT NULL,


    job_title VARCHAR(256) NOT NULL,


    description TEXT,


    requirements JSONB,


    location VARCHAR(256),


    salary VARCHAR(128),


    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,


    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,


    CONSTRAINT fk_job_user
        FOREIGN KEY(user_id)
            REFERENCES users(id)
            ON DELETE CASCADE
);



COMMENT ON TABLE job
IS 'Job information table';


COMMENT ON COLUMN job.user_id
IS 'Owner user id';


COMMENT ON COLUMN job.requirements
IS 'Job requirements JSON data';



-- Query jobs by owner.
CREATE INDEX idx_job_user_id
    ON job(user_id);



-- Company query.
CREATE INDEX idx_job_company
    ON job(company_name);



-- Job title query.
CREATE INDEX idx_job_title
    ON job(job_title);



-- Location query.
CREATE INDEX idx_job_location
    ON job(location);



-- JSONB job requirements index.
CREATE INDEX idx_job_requirements_json
    ON job
    USING GIN(requirements);