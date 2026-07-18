CREATE TABLE resumeDocument
(

    id BIGINT PRIMARY KEY AUTO_INCREMENT,


    userId BIGINT NOT NULL,


    resumeName VARCHAR(256)
        COMMENT 'resume name',


    fileUrl VARCHAR(1024)
        COMMENT 'file storage url',


    fileType VARCHAR(50)
        COMMENT 'PDF DOCX',


    content TEXT
        COMMENT 'parsed resume content',


    version INT DEFAULT 1,


    isDefault TINYINT DEFAULT 0,


    createTime DATETIME DEFAULT CURRENT_TIMESTAMP,


    updateTime DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,


    isDelete TINYINT DEFAULT 0,


    CONSTRAINT fk_resume_user

    FOREIGN KEY(userId)

    REFERENCES user(id),


    INDEX idx_resume_userId(userId)

);