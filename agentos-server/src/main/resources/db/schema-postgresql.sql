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
    settings_payload TEXT NOT NULL DEFAULT '{}',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_status VARCHAR(32) NOT NULL DEFAULT 'UNTESTED',
    last_error TEXT NOT NULL DEFAULT '',
    last_checked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
-- 兼容已初始化的 PostgreSQL 数据库；CREATE TABLE IF NOT EXISTS 不会为旧表补字段。
ALTER TABLE model_providers
    ADD COLUMN IF NOT EXISTS settings_payload TEXT NOT NULL DEFAULT '{}';
COMMENT ON COLUMN model_providers.provider_id IS '模型服务商配置的唯一标识';
COMMENT ON COLUMN model_providers.display_name IS '管理页面展示的模型服务商名称';
COMMENT ON COLUMN model_providers.provider_type IS '服务商类型：OPENAI、DEEPSEEK、GLM、QWEN、MINIMAX、CC_SWITCH 或 OPENAI_COMPATIBLE';
COMMENT ON COLUMN model_providers.protocol IS '模型调用协议，当前支持 CHAT_COMPLETIONS';
COMMENT ON COLUMN model_providers.endpoint IS '模型服务兼容接口的完整 HTTPS 或 HTTP 地址';
COMMENT ON COLUMN model_providers.encrypted_api_key IS '使用 AES-256-GCM 加密并以 Base64 编码的 API 密钥';
COMMENT ON COLUMN model_providers.models_payload IS '该服务商可选模型标识列表的 JSON 文本';
COMMENT ON COLUMN model_providers.default_model IS '该服务商配置用于连接测试和初始选择的模型标识';
COMMENT ON COLUMN model_providers.response_format IS '兼容接口的响应格式策略枚举值';
COMMENT ON COLUMN model_providers.reasoning_split IS '是否从 reasoning_content 分离模型思考内容';
COMMENT ON COLUMN model_providers.settings_payload IS '模型上下文窗口、输出上限、工具轮数、多模态、思考模式及采样参数的 JSON 配置';
COMMENT ON COLUMN model_providers.enabled IS '配置是否允许被运行时路由使用';
COMMENT ON COLUMN model_providers.last_status IS '最近连接测试状态：UNTESTED、CONNECTED 或 FAILED';
COMMENT ON COLUMN model_providers.last_error IS '最近连接测试的脱敏错误摘要';
COMMENT ON COLUMN model_providers.last_checked_at IS '最近连接测试完成时间';
COMMENT ON COLUMN model_providers.created_at IS '配置创建时间';
COMMENT ON COLUMN model_providers.updated_at IS '配置最近更新时间';
COMMENT ON COLUMN model_providers.version IS 'MyBatis-Plus 乐观锁版本号';

-- 任务已改为显式携带平台模型 ID，不再保留 Planner/Chat 全局路由配置。
DROP TABLE IF EXISTS model_routes;

CREATE TABLE IF NOT EXISTS automation_tasks (
    automation_id VARCHAR(128) PRIMARY KEY,
    team_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    name VARCHAR(80) NOT NULL,
    prompt TEXT NOT NULL,
    agent_id VARCHAR(128) NOT NULL DEFAULT 'main-agent',
    model_id VARCHAR(256) NOT NULL,
    approval_mode VARCHAR(32) NOT NULL,
    desktop_client_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    workspace_name VARCHAR(256) NOT NULL,
    trigger_payload TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_trigger_at TIMESTAMPTZ NULL,
    last_trigger_at TIMESTAMPTZ NULL,
    deleted_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
COMMENT ON COLUMN automation_tasks.automation_id IS '自动化任务唯一标识';
COMMENT ON COLUMN automation_tasks.team_id IS '任务所属团队标识，用于租户隔离';
COMMENT ON COLUMN automation_tasks.user_id IS '任务创建和管理用户标识';
COMMENT ON COLUMN automation_tasks.name IS '用户可见的自动化任务名称，长度不超过 80 字符';
COMMENT ON COLUMN automation_tasks.prompt IS '每次触发时交给 Agent 的任务指令，长度不超过 2000 字符';
COMMENT ON COLUMN automation_tasks.agent_id IS '执行任务使用的 Agent 标识，首版固定为 main-agent';
COMMENT ON COLUMN automation_tasks.model_id IS '执行任务使用的已配置平台模型标识';
COMMENT ON COLUMN automation_tasks.approval_mode IS '工具权限模式：REQUEST_APPROVAL、RISK_BASED 或 FULL_ACCESS';
COMMENT ON COLUMN automation_tasks.desktop_client_id IS '负责读取本地工作区并派发执行的桌面客户端稳定标识';
COMMENT ON COLUMN automation_tasks.workspace_id IS '桌面客户端本地保存的工作区不透明标识，不包含绝对路径';
COMMENT ON COLUMN automation_tasks.workspace_name IS '工作区展示名称快照，不包含本地绝对路径';
COMMENT ON COLUMN automation_tasks.trigger_payload IS '周期、Cron 或间隔触发规则及 IANA 时区的 JSON 文本';
COMMENT ON COLUMN automation_tasks.enabled IS '是否允许定时触发和手动立即执行';
COMMENT ON COLUMN automation_tasks.next_trigger_at IS '按任务触发规则计算的下一次计划触发时间，停用时为 NULL';
COMMENT ON COLUMN automation_tasks.last_trigger_at IS '最近一次由调度器或用户触发的计划时间';
COMMENT ON COLUMN automation_tasks.deleted_at IS '软删除时间，NULL 表示任务仍可见';
COMMENT ON COLUMN automation_tasks.created_at IS '任务创建时间';
COMMENT ON COLUMN automation_tasks.updated_at IS '任务最近更新时间';
COMMENT ON COLUMN automation_tasks.version IS '任务乐观锁版本号';
CREATE INDEX IF NOT EXISTS idx_automation_tasks_owner ON automation_tasks(team_id, user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_automation_tasks_due ON automation_tasks(next_trigger_at) WHERE enabled AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS automation_executions (
    execution_id VARCHAR(128) PRIMARY KEY,
    automation_id VARCHAR(128) NOT NULL,
    team_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    task_name VARCHAR(80) NOT NULL,
    trigger_source VARCHAR(32) NOT NULL,
    status VARCHAR(48) NOT NULL,
    scheduled_key VARCHAR(320) NOT NULL UNIQUE,
    scheduled_at TIMESTAMPTZ NOT NULL,
    desktop_client_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    workspace_name VARCHAR(256) NOT NULL,
    claimed_at TIMESTAMPTZ NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    session_id VARCHAR(128) NOT NULL DEFAULT '',
    run_id VARCHAR(128) NOT NULL DEFAULT '',
    invocation_id VARCHAR(128) NOT NULL DEFAULT '',
    started_at TIMESTAMPTZ NULL,
    finished_at TIMESTAMPTZ NULL,
    result_excerpt TEXT NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
COMMENT ON COLUMN automation_executions.execution_id IS '单次自动化执行唯一标识';
COMMENT ON COLUMN automation_executions.automation_id IS '产生该执行的自动化任务标识，任务软删除后仍保留';
COMMENT ON COLUMN automation_executions.team_id IS '执行所属团队标识，用于租户隔离';
COMMENT ON COLUMN automation_executions.user_id IS '执行所属用户标识';
COMMENT ON COLUMN automation_executions.task_name IS '触发时的任务名称快照';
COMMENT ON COLUMN automation_executions.trigger_source IS '触发来源：SCHEDULED 或 MANUAL';
COMMENT ON COLUMN automation_executions.status IS '执行状态，包括排队、领取、运行、等待审批、终态及跳过原因';
COMMENT ON COLUMN automation_executions.scheduled_key IS '自动触发去重键，避免多 Server 实例重复创建同一次执行';
COMMENT ON COLUMN automation_executions.scheduled_at IS '本次执行对应的计划触发时间';
COMMENT ON COLUMN automation_executions.desktop_client_id IS '本次执行指定领取的桌面客户端标识';
COMMENT ON COLUMN automation_executions.workspace_id IS '触发时绑定的本地工作区不透明标识';
COMMENT ON COLUMN automation_executions.workspace_name IS '触发时绑定的工作区名称快照';
COMMENT ON COLUMN automation_executions.claimed_at IS '桌面客户端领取执行的时间';
COMMENT ON COLUMN automation_executions.lease_expires_at IS '桌面领取租约失效时间，超时后执行转为跳过';
COMMENT ON COLUMN automation_executions.session_id IS '本次执行创建的独立 Chat 会话标识，未启动时为空字符串';
COMMENT ON COLUMN automation_executions.run_id IS '后台 Agent Run 标识，未启动时为空字符串';
COMMENT ON COLUMN automation_executions.invocation_id IS 'Agent 调用标识，尚未产生时为空字符串';
COMMENT ON COLUMN automation_executions.started_at IS 'Agent Run 实际开始时间';
COMMENT ON COLUMN automation_executions.finished_at IS '执行进入最终状态的时间';
COMMENT ON COLUMN automation_executions.result_excerpt IS '完成结果的有界文本摘要，完整内容保存在会话事件中';
COMMENT ON COLUMN automation_executions.error_message IS '失败、取消或跳过原因的有界错误摘要';
COMMENT ON COLUMN automation_executions.created_at IS '执行记录创建时间';
COMMENT ON COLUMN automation_executions.updated_at IS '执行记录最近更新时间';
COMMENT ON COLUMN automation_executions.version IS '执行记录乐观锁版本号';
CREATE INDEX IF NOT EXISTS idx_automation_executions_owner ON automation_executions(team_id, user_id, scheduled_at DESC);
CREATE INDEX IF NOT EXISTS idx_automation_executions_claim ON automation_executions(desktop_client_id, status, scheduled_at);
CREATE INDEX IF NOT EXISTS idx_automation_executions_task ON automation_executions(automation_id, scheduled_at DESC);

CREATE TABLE IF NOT EXISTS automation_clients (
    client_key VARCHAR(400) PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL,
    team_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    app_version VARCHAR(64) NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
COMMENT ON COLUMN automation_clients.client_key IS '团队、用户与桌面客户端标识组合后的唯一键';
COMMENT ON COLUMN automation_clients.client_id IS 'AgentOS Desktop 安装实例的稳定随机标识';
COMMENT ON COLUMN automation_clients.team_id IS '桌面客户端当前登录用户所属团队标识';
COMMENT ON COLUMN automation_clients.user_id IS '桌面客户端当前登录用户标识';
COMMENT ON COLUMN automation_clients.platform IS '桌面操作系统平台：macos、windows 或 linux';
COMMENT ON COLUMN automation_clients.app_version IS '桌面客户端上报的应用版本';
COMMENT ON COLUMN automation_clients.last_seen_at IS '最近一次成功心跳时间，用于判断客户端是否在线';
COMMENT ON COLUMN automation_clients.created_at IS '客户端首次登记时间';
COMMENT ON COLUMN automation_clients.updated_at IS '客户端登记信息最近更新时间';
CREATE INDEX IF NOT EXISTS idx_automation_clients_seen ON automation_clients(team_id, user_id, last_seen_at DESC);
