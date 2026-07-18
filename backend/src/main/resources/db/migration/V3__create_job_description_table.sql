CREATE TABLE jobDescription
(

    id BIGINT PRIMARY KEY AUTO_INCREMENT,


    userId BIGINT NOT NULL,


    companyName VARCHAR(256),


    jobTitle VARCHAR(256),


    jobUrl VARCHAR(1024),


    description TEXT,


    requirements TEXT,


    location VARCHAR(256),


    salary VARCHAR(128),


    createTime DATETIME DEFAULT CURRENT_TIMESTAMP,


    updateTime DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,


    isDelete TINYINT DEFAULT 0,


    CONSTRAINT fk_job_user

    FOREIGN KEY(userId)

    REFERENCES user(id),


    INDEX idx_job_userId(userId)

);