CREATE TABLE IF NOT EXISTS agent_events (
    event_sequence BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL UNIQUE,
    session_id VARCHAR(128) NOT NULL,
    invocation_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    message TEXT NOT NULL,
    event_data TEXT NOT NULL,
    event_actions TEXT NOT NULL
);
COMMENT ON COLUMN agent_events.event_sequence IS '事件写入顺序，由数据库生成并用于同时间事件的稳定排序';
COMMENT ON COLUMN agent_events.event_id IS '领域事件唯一标识';
COMMENT ON COLUMN agent_events.session_id IS '事件所属会话标识';
COMMENT ON COLUMN agent_events.invocation_id IS '事件所属单次 Agent 调用标识';
COMMENT ON COLUMN agent_events.agent_id IS '产生事件的 Agent 标识';
COMMENT ON COLUMN agent_events.occurred_at IS '事件发生时间，使用带时区时间戳';
COMMENT ON COLUMN agent_events.event_type IS '领域事件类型枚举值';
COMMENT ON COLUMN agent_events.message IS '供用户或运维人员阅读的事件说明';
COMMENT ON COLUMN agent_events.event_data IS '事件结构化数据的 JSON 文本';
COMMENT ON COLUMN agent_events.event_actions IS '事件状态变更指令的 JSON 文本';
CREATE INDEX IF NOT EXISTS idx_agent_events_invocation ON agent_events(invocation_id, occurred_at, event_sequence);
CREATE INDEX IF NOT EXISTS idx_agent_events_session ON agent_events(session_id, occurred_at, event_sequence);

CREATE TABLE IF NOT EXISTS agent_checkpoints (
    invocation_id VARCHAR(128) PRIMARY KEY,
    payload TEXT NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL
);
COMMENT ON COLUMN agent_checkpoints.invocation_id IS '可恢复 Agent 调用的唯一标识';
COMMENT ON COLUMN agent_checkpoints.payload IS 'Agent Checkpoint 完整 JSON 快照';
COMMENT ON COLUMN agent_checkpoints.saved_at IS 'Checkpoint 最近保存时间';

CREATE TABLE IF NOT EXISTS agent_continuations (
    invocation_id VARCHAR(128) PRIMARY KEY,
    payload TEXT NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL
);
COMMENT ON COLUMN agent_continuations.invocation_id IS '等待续跑的 Agent 调用唯一标识';
COMMENT ON COLUMN agent_continuations.payload IS 'MainAgent 或 ReactAgent 续跑状态的 JSON 快照';
COMMENT ON COLUMN agent_continuations.saved_at IS '续跑状态最近保存时间';

CREATE TABLE IF NOT EXISTS session_usage (
    session_id VARCHAR(128) PRIMARY KEY,
    model_calls BIGINT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN session_usage.session_id IS '累计模型用量对应的会话标识';
COMMENT ON COLUMN session_usage.model_calls IS '会话累计模型调用次数';
COMMENT ON COLUMN session_usage.prompt_tokens IS '会话累计输入 Token 数';
COMMENT ON COLUMN session_usage.completion_tokens IS '会话累计输出 Token 数';
COMMENT ON COLUMN session_usage.updated_at IS '用量记录最近更新时间';

CREATE TABLE IF NOT EXISTS agent_sessions (
    session_id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    state_payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_active_at TIMESTAMPTZ NOT NULL
);
COMMENT ON COLUMN agent_sessions.session_id IS '持续对话会话的唯一标识';
COMMENT ON COLUMN agent_sessions.user_id IS '会话所属用户标识';
COMMENT ON COLUMN agent_sessions.state_payload IS '会话结构化状态的 JSON 文本';
COMMENT ON COLUMN agent_sessions.created_at IS '会话创建时间';
COMMENT ON COLUMN agent_sessions.last_active_at IS '会话最近活跃时间';
CREATE INDEX IF NOT EXISTS idx_agent_sessions_active ON agent_sessions(last_active_at DESC);

CREATE TABLE IF NOT EXISTS users (
    username VARCHAR(128) PRIMARY KEY,
    password_hash VARCHAR(512) NOT NULL,
    roles TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
COMMENT ON COLUMN users.username IS '登录用户名，作为账户唯一标识';
COMMENT ON COLUMN users.password_hash IS '密码哈希值，仅服务端鉴权使用且不得回传客户端';
COMMENT ON COLUMN users.roles IS '逗号分隔的账户角色集合';
COMMENT ON COLUMN users.created_at IS '账户创建时间';

CREATE TABLE IF NOT EXISTS memory_records (
    record_key VARCHAR(320) PRIMARY KEY,
    record_kind VARCHAR(32) NOT NULL,
    business_id VARCHAR(256) NOT NULL,
    team_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL DEFAULT '',
    record_version INTEGER NOT NULL DEFAULT 1,
    record_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    sort_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_memory_record_kind_id UNIQUE(record_kind, business_id)
);
COMMENT ON COLUMN memory_records.record_key IS '记忆类型与业务标识组合后的全局唯一键';
COMMENT ON COLUMN memory_records.record_kind IS '记忆记录类型：TURN、ATOMIC、VECTOR、SCENARIO、PROFILE 或 JOB';
COMMENT ON COLUMN memory_records.business_id IS '对应领域对象的业务唯一标识';
COMMENT ON COLUMN memory_records.team_id IS '记忆隔离使用的团队标识';
COMMENT ON COLUMN memory_records.user_id IS '记忆隔离使用的用户标识';
COMMENT ON COLUMN memory_records.agent_id IS '记忆隔离使用的 Agent 标识';
COMMENT ON COLUMN memory_records.session_id IS '记忆来源或所属会话标识';
COMMENT ON COLUMN memory_records.task_id IS '可选任务标识，空字符串表示跨任务可见';
COMMENT ON COLUMN memory_records.record_version IS '领域对象版本号，用于阻止旧数据覆盖新版本';
COMMENT ON COLUMN memory_records.record_status IS '记录状态，例如 ACTIVE、INVALIDATED、SUPERSEDED、PENDING 或 COMPLETED';
COMMENT ON COLUMN memory_records.sort_at IS '该类型记录用于排序的业务时间';
COMMENT ON COLUMN memory_records.expires_at IS '可选失效时间，NULL 表示永久有效';
COMMENT ON COLUMN memory_records.payload IS '完整领域对象的 JSON 文本';
COMMENT ON COLUMN memory_records.created_at IS '记录首次创建时间';
COMMENT ON COLUMN memory_records.updated_at IS '记录最近更新时间';
CREATE INDEX IF NOT EXISTS idx_memory_actor_kind ON memory_records(team_id, user_id, agent_id, record_kind, sort_at DESC);
CREATE INDEX IF NOT EXISTS idx_memory_session_kind ON memory_records(session_id, record_kind, sort_at DESC);
CREATE INDEX IF NOT EXISTS idx_memory_expiry ON memory_records(record_kind, expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS model_providers (
    provider_id VARCHAR(128) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL UNIQUE,
    provider_type VARCHAR(32) NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    endpoint TEXT NOT NULL,
    encrypted_api_key TEXT NOT NULL,
    models_payload TEXT NOT NULL,
    default_model VARCHAR(256) NOT NULL,
    response_format VARCHAR(32) NOT NULL,
    reasoning_split BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_status VARCHAR(32) NOT NULL DEFAULT 'UNTESTED',
    last_error TEXT NOT NULL DEFAULT '',
    last_checked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
COMMENT ON COLUMN model_providers.provider_id IS '模型服务商配置的唯一标识';
COMMENT ON COLUMN model_providers.display_name IS '管理页面展示的模型服务商名称';
COMMENT ON COLUMN model_providers.provider_type IS '服务商类型，例如 OPENAI、MINIMAX、CC_SWITCH 或 OPENAI_COMPATIBLE';
COMMENT ON COLUMN model_providers.protocol IS '模型调用协议，当前支持 CHAT_COMPLETIONS';
COMMENT ON COLUMN model_providers.endpoint IS '模型服务兼容接口的完整 HTTPS 或 HTTP 地址';
COMMENT ON COLUMN model_providers.encrypted_api_key IS '使用 AES-256-GCM 加密并以 Base64 编码的 API 密钥';
COMMENT ON COLUMN model_providers.models_payload IS '该服务商可选模型标识列表的 JSON 文本';
COMMENT ON COLUMN model_providers.default_model IS '未在路由中覆盖时使用的默认模型标识';
COMMENT ON COLUMN model_providers.response_format IS '兼容接口的响应格式策略枚举值';
COMMENT ON COLUMN model_providers.reasoning_split IS '是否从 reasoning_content 分离模型思考内容';
COMMENT ON COLUMN model_providers.enabled IS '配置是否允许被运行时路由使用';
COMMENT ON COLUMN model_providers.last_status IS '最近连接测试状态：UNTESTED、CONNECTED 或 FAILED';
COMMENT ON COLUMN model_providers.last_error IS '最近连接测试的脱敏错误摘要';
COMMENT ON COLUMN model_providers.last_checked_at IS '最近连接测试完成时间';
COMMENT ON COLUMN model_providers.created_at IS '配置创建时间';
COMMENT ON COLUMN model_providers.updated_at IS '配置最近更新时间';
COMMENT ON COLUMN model_providers.version IS 'MyBatis-Plus 乐观锁版本号';

CREATE TABLE IF NOT EXISTS model_routes (
    route_key VARCHAR(32) PRIMARY KEY,
    provider_id VARCHAR(128) NOT NULL,
    model_id VARCHAR(256) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_model_routes_provider FOREIGN KEY(provider_id) REFERENCES model_providers(provider_id)
);
COMMENT ON COLUMN model_routes.route_key IS '运行时模型用途键，当前为 planner 或 chat';
COMMENT ON COLUMN model_routes.provider_id IS '该用途当前绑定的模型服务商标识';
COMMENT ON COLUMN model_routes.model_id IS '该用途实际调用的模型标识';
COMMENT ON COLUMN model_routes.updated_at IS '路由最近更新时间';
COMMENT ON COLUMN model_routes.version IS 'MyBatis-Plus 乐观锁版本号';
