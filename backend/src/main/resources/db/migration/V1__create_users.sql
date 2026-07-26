CREATE TABLE users
(
    id BIGSERIAL PRIMARY KEY,

    username VARCHAR(256),

    user_account VARCHAR(256) NOT NULL,

    avatar_url VARCHAR(512),

    gender INTEGER,

    user_password VARCHAR(512) NOT NULL,

    email VARCHAR(256),

    phone VARCHAR(128),

    user_status INTEGER DEFAULT 0,

    user_role INTEGER DEFAULT 0,

    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    is_delete INTEGER DEFAULT 0,


    CONSTRAINT uk_users_account
    UNIQUE(user_account)
);


COMMENT ON TABLE users IS 'User information table';

COMMENT ON COLUMN users.user_account 
IS 'User login account';

COMMENT ON COLUMN users.user_password 
IS 'Encrypted password';

COMMENT ON COLUMN users.user_role
IS '0 normal user, 1 administrator';



-- 用户账号查询索引
CREATE INDEX idx_users_account
ON users(user_account);


-- 用户角色查询索引
CREATE INDEX idx_users_role
ON users(user_role);


-- 删除状态索引
CREATE INDEX idx_users_delete
ON users(is_delete);