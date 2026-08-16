CREATE TABLE IF NOT EXISTS memory_schema_migrations (
    -- 已成功应用的数据库结构版本号。
    version INTEGER PRIMARY KEY,
    -- 迁移资源名称，用于排查结构来源。
    description TEXT NOT NULL,
    -- 迁移提交时间，Unix epoch 毫秒。
    applied_at INTEGER NOT NULL
);
