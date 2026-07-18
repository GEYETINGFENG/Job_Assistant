CREATE TABLE applicationEvent
(

    id BIGINT PRIMARY KEY AUTO_INCREMENT,


    applicationId BIGINT NOT NULL,


    oldStatus VARCHAR(50),


    newStatus VARCHAR(50),


    description VARCHAR(512),


    eventTime DATETIME DEFAULT CURRENT_TIMESTAMP,


    CONSTRAINT fk_event_application

    FOREIGN KEY(applicationId)

    REFERENCES application(id),


    INDEX idx_event_applicationId(applicationId)

);