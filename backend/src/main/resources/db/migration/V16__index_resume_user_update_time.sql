-- 简历列表：WHERE user_id = ? AND is_delete = 0 ORDER BY update_time DESC, id DESC LIMIT ?
-- 原索引 idx_resume_user_active 只覆盖 user_id，查询要读出该用户全部有效简历再排序（EXPLAIN 中 Index Scan 后挂 Sort）。
-- 复合索引的顺序与 ORDER BY 一致，数据库按索引顺序读到 LIMIT 条就停止，Sort 节点消失。
CREATE INDEX idx_resume_user_active_update_time
    ON resume (user_id, update_time DESC, id DESC)
    WHERE is_delete = 0;

-- 新索引的前导列同样是 user_id，可以完全替代旧的部分索引；删除旧索引，减少写入时的索引维护开销。
-- 删除索引不影响旧版本代码的正确性，滚动发布期间新旧实例都可以正常运行。
DROP INDEX IF EXISTS idx_resume_user_active;
