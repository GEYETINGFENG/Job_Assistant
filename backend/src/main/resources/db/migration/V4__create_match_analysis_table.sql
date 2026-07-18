CREATE TABLE matchAnalysis
(

    id BIGINT PRIMARY KEY AUTO_INCREMENT,


    userId BIGINT NOT NULL,


    resumeId BIGINT NOT NULL,


    jobId BIGINT NOT NULL,


    matchScore INT
        COMMENT '0-100 score',


    skillGap TEXT,


    suggestion TEXT,


    analysisResult TEXT,


    createTime DATETIME DEFAULT CURRENT_TIMESTAMP,


    CONSTRAINT fk_match_user

    FOREIGN KEY(userId)

    REFERENCES user(id),


    CONSTRAINT fk_match_resume

    FOREIGN KEY(resumeId)

    REFERENCES resumeDocument(id),


    CONSTRAINT fk_match_job

    FOREIGN KEY(jobId)

    REFERENCES jobDescription(id),


    INDEX idx_match_userId(userId),

    INDEX idx_match_resumeId(resumeId),

    INDEX idx_match_jobId(jobId)

);