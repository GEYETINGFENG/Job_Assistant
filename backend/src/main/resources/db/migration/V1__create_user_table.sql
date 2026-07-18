CREATE TABLE user
(
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'user id',

    username VARCHAR(256)
        COMMENT 'username',


    userAccount VARCHAR(256)
        NOT NULL UNIQUE
        COMMENT 'login account',


    avatarUrl VARCHAR(1024)
        COMMENT 'avatar url',


    gender INT
        COMMENT 'gender',


    userPassword VARCHAR(512)
        NOT NULL
        COMMENT 'encrypted password',


    email VARCHAR(512)
        COMMENT 'email',


    userStatus INT
        DEFAULT 0
        COMMENT '0-normal',


    phone VARCHAR(128)
        COMMENT 'phone',


    profile VARCHAR(512)
        COMMENT 'personal profile',


    location VARCHAR(256)
        COMMENT 'current location',


    githubUrl VARCHAR(512)
        COMMENT 'github url',


    linkedinUrl VARCHAR(512)
        COMMENT 'linkedin url',


    careerGoal VARCHAR(256)
        COMMENT 'career goal',


    education VARCHAR(256)
        COMMENT 'education background',


    userRole INT
        DEFAULT 0
        COMMENT '0-user 1-admin',


    createTime DATETIME
        DEFAULT CURRENT_TIMESTAMP,


    updateTime DATETIME
        DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,


    isDelete TINYINT
        DEFAULT 0,


    INDEX idx_userAccount(userAccount),

    INDEX idx_email(email)

);