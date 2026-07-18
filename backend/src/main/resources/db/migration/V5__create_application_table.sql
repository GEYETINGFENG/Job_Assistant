CREATE TABLE application
(

    id BIGINT PRIMARY KEY AUTO_INCREMENT,


    userId BIGINT NOT NULL,


    jobId BIGINT NOT NULL,


    resumeId BIGINT NOT NULL,


    status VARCHAR(50)
        COMMENT 'APPLIED INTERVIEW OFFER REJECTED',


    applyTime DATETIME,


    remark TEXT,


    createTime DATETIME DEFAULT CURRENT_TIMESTAMP,


    updateTime DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,


    CONSTRAINT fk_application_user

    FOREIGN KEY(userId)

    REFERENCES user(id),


    CONSTRAINT fk_application_job

    FOREIGN KEY(jobId)

    REFERENCES jobDescription(id),


    CONSTRAINT fk_application_resume

    FOREIGN KEY(resumeId)

    REFERENCES resumeDocument(id),


    INDEX idx_application_userId(userId),

    INDEX idx_application_jobId(jobId)

);