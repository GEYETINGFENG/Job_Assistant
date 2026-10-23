-- 申请状态机：status 从裸 INTEGER 改为可读的状态字符串，并增加状态变更事件历史。
-- 合法转移规则只在应用层的 ApplicationStatus 转移表中定义；数据库 CHECK 只保证状态值本身合法。

-- 1. application.status: INTEGER -> VARCHAR
-- 旧代码只会写入 0（新建申请），没有定义其他整数的含义，因此全部迁移为 APPLIED。
ALTER TABLE application
    ALTER COLUMN status DROP DEFAULT;

ALTER TABLE application
    ALTER COLUMN status TYPE VARCHAR(32)
        USING 'APPLIED';

ALTER TABLE application
    ALTER COLUMN status SET DEFAULT 'APPLIED',
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE application
    ADD CONSTRAINT ck_application_status
        CHECK (status IN ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'));

ALTER TABLE application
    ADD COLUMN update_time TIMESTAMP;

UPDATE application
SET update_time = COALESCE(apply_time, CURRENT_TIMESTAMP);

ALTER TABLE application
    ALTER COLUMN update_time SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN update_time SET NOT NULL;

COMMENT ON COLUMN application.status
IS 'Application status: APPLIED / SCREENING / INTERVIEW / OFFER / ACCEPTED / REJECTED / WITHDRAWN';

COMMENT ON COLUMN application.update_time
IS 'Time of the latest status change';

-- 原 idx_application_status 只按状态过滤，看板实际查询是“某个用户的某个状态”。
DROP INDEX IF EXISTS idx_application_status;

CREATE INDEX idx_application_user_status
    ON application(user_id, status);


-- 2. application_event: 每次状态变化记一行，和状态更新在同一事务中写入。
CREATE TABLE application_event
(
    id BIGSERIAL PRIMARY KEY,

    application_id BIGINT NOT NULL,

    -- 创建申请时没有来源状态，因此可以为空。
    from_status VARCHAR(32),

    to_status VARCHAR(32) NOT NULL,

    -- 执行这次变更的用户。
    operator_user_id BIGINT NOT NULL,

    -- 可选备注，例如被拒原因。
    note VARCHAR(512),

    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_application_event_application
        FOREIGN KEY(application_id)
            REFERENCES application(id)
            ON DELETE CASCADE,

    CONSTRAINT fk_application_event_operator
        FOREIGN KEY(operator_user_id)
            REFERENCES users(id)
            ON DELETE CASCADE,

    CONSTRAINT ck_application_event_from_status
        CHECK (from_status IS NULL
            OR from_status IN ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')),

    CONSTRAINT ck_application_event_to_status
        CHECK (to_status IN ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'))
);

COMMENT ON TABLE application_event
IS 'Append-only history of application status changes';

-- 游标分页：WHERE application_id = ? AND id < ? ORDER BY id DESC LIMIT ?
-- 索引顺序与查询一致，直接按索引定位到游标位置，不需要扫描并丢弃前面的行。
CREATE INDEX idx_application_event_application_id
    ON application_event(application_id, id DESC);

-- 已有申请补一条创建事件，保证每个申请的历史都从 APPLIED 开始。
INSERT INTO application_event (application_id, from_status, to_status, operator_user_id, create_time)
SELECT id, NULL, 'APPLIED', user_id, COALESCE(apply_time, CURRENT_TIMESTAMP)
FROM application;
