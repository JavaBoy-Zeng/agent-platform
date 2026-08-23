-- SQLite 无法持久化字段注释；以下业务注释与字段定义紧邻保存。

-- L0 轮次的稳定业务幂等键，由调用方 invocation/run 标识派生。
ALTER TABLE memory_turns ADD COLUMN business_key TEXT NOT NULL DEFAULT '';

-- 原子记忆生命周期状态：ACTIVE、INVALIDATED 或 SUPERSEDED。
ALTER TABLE memory_atomic ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE';
-- 记忆开始生效时间，Unix epoch 毫秒。
ALTER TABLE memory_atomic ADD COLUMN valid_from INTEGER NOT NULL DEFAULT 0;
-- 记忆到期时间，Unix epoch 毫秒；NULL 表示永不过期。
ALTER TABLE memory_atomic ADD COLUMN expires_at INTEGER;
-- 替代当前记忆的新记忆标识；非 SUPERSEDED 状态为空字符串。
ALTER TABLE memory_atomic ADD COLUMN superseded_by_id TEXT NOT NULL DEFAULT '';

CREATE TABLE memory_atomic_sources (
    -- 所属原子记忆标识。
    memory_id TEXT NOT NULL,
    -- 产生或修订该记忆的轮次、人工纠错或系统操作来源标识。
    source_id TEXT NOT NULL,
    -- 来源首次关联时间，Unix epoch 毫秒。
    created_at INTEGER NOT NULL,
    PRIMARY KEY (memory_id, source_id),
    FOREIGN KEY (memory_id) REFERENCES memory_atomic(id) ON DELETE CASCADE
);

CREATE TABLE memory_embeddings (
    -- 所属原子记忆标识。
    memory_id TEXT NOT NULL,
    -- 生成向量的模型稳定标识，用于模型升级期间并存和隔离。
    model TEXT NOT NULL,
    -- 向量对应的原子记忆内容版本。
    content_version INTEGER NOT NULL,
    -- 向量维度，用于读取时校验二进制数据完整性。
    dimension INTEGER NOT NULL,
    -- IEEE-754 double 大端序列化后的向量二进制。
    vector BLOB NOT NULL,
    -- 向量最后更新时间，Unix epoch 毫秒。
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (memory_id, model),
    FOREIGN KEY (memory_id) REFERENCES memory_atomic(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_memory_turns_actor_business_key
    ON memory_turns(team_id, user_id, agent_id, business_key)
    WHERE business_key <> '';

CREATE INDEX idx_memory_atomic_active_scope
    ON memory_atomic(team_id, user_id, agent_id, task_id, status, expires_at, updated_at DESC);

CREATE INDEX idx_memory_atomic_sources_source
    ON memory_atomic_sources(source_id, memory_id);

CREATE INDEX idx_memory_embeddings_model_version
    ON memory_embeddings(model, content_version, memory_id);
