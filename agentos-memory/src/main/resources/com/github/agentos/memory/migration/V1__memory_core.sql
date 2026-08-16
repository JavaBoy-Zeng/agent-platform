CREATE TABLE memory_turns (
    -- L0 对话轮次唯一标识，也是成功回流的幂等键。
    id TEXT PRIMARY KEY,
    -- 轮次所属团队标识，用于租户隔离。
    team_id TEXT NOT NULL,
    -- 轮次所属用户标识。
    user_id TEXT NOT NULL,
    -- 处理该轮次的 Agent 标识。
    agent_id TEXT NOT NULL,
    -- 轮次所属会话标识，仅允许在同一会话内召回 L0。
    session_id TEXT NOT NULL,
    -- 可选任务标识，空字符串表示不绑定具体任务。
    task_id TEXT NOT NULL DEFAULT '',
    -- 本轮原始用户输入。
    user_input TEXT NOT NULL,
    -- 本轮 Agent 最终成功输出。
    assistant_output TEXT NOT NULL,
    -- 轮次成功完成时间，Unix epoch 毫秒。
    completed_at INTEGER NOT NULL
);

CREATE TABLE memory_turn_tool_outputs (
    -- 所属 L0 对话轮次标识。
    turn_id TEXT NOT NULL,
    -- 工具输出在原始轮次中的零基顺序。
    output_index INTEGER NOT NULL,
    -- 工具执行结果或摘要文本。
    content TEXT NOT NULL,
    PRIMARY KEY (turn_id, output_index),
    FOREIGN KEY (turn_id) REFERENCES memory_turns(id) ON DELETE CASCADE
);

CREATE TABLE memory_atomic (
    -- L1 原子记忆唯一标识。
    id TEXT PRIMARY KEY,
    -- 原子记忆所属团队标识，用于租户隔离。
    team_id TEXT NOT NULL,
    -- 原子记忆所属用户标识。
    user_id TEXT NOT NULL,
    -- 原子记忆所属 Agent 标识。
    agent_id TEXT NOT NULL,
    -- 产生该记忆的会话标识，用于审计来源。
    session_id TEXT NOT NULL,
    -- 可选任务标识，空字符串表示可以跨任务召回。
    task_id TEXT NOT NULL DEFAULT '',
    -- 记忆类型枚举名称，如 FACT、PREFERENCE 或 CONSTRAINT。
    memory_type TEXT NOT NULL,
    -- 可独立检索的记忆正文。
    content TEXT NOT NULL,
    -- 模型对记忆准确性的置信度，取值范围 0 到 1。
    confidence REAL NOT NULL,
    -- 记忆重要程度，取值范围 0 到 10。
    priority INTEGER NOT NULL,
    -- 记忆内容版本，从 1 开始递增，用于阻止旧结果覆盖。
    version INTEGER NOT NULL,
    -- 最近一次生成该版本的 L0 轮次标识。
    source_turn_id TEXT NOT NULL,
    -- 首次创建时间，Unix epoch 毫秒。
    created_at INTEGER NOT NULL,
    -- 最后更新时间，Unix epoch 毫秒。
    updated_at INTEGER NOT NULL
);

CREATE TABLE memory_scenarios (
    -- L2 场景记忆唯一标识。
    id TEXT PRIMARY KEY,
    -- 场景所属团队标识，用于租户隔离。
    team_id TEXT NOT NULL,
    -- 场景所属用户标识。
    user_id TEXT NOT NULL,
    -- 场景所属 Agent 标识。
    agent_id TEXT NOT NULL,
    -- 最近生成该场景的会话标识，用于审计来源。
    session_id TEXT NOT NULL,
    -- 可选任务标识，空字符串表示 Agent 级通用场景。
    task_id TEXT NOT NULL DEFAULT '',
    -- 稳定场景名称，如 task:checkout。
    name TEXT NOT NULL,
    -- 场景的 Markdown 摘要正文。
    content TEXT NOT NULL,
    -- 场景内容版本，从 1 开始递增。
    version INTEGER NOT NULL,
    -- 最后更新时间，Unix epoch 毫秒。
    updated_at INTEGER NOT NULL
);

CREATE TABLE memory_profiles (
    -- L3 长期画像唯一标识。
    id TEXT PRIMARY KEY,
    -- 画像所属团队标识，用于租户隔离。
    team_id TEXT NOT NULL,
    -- 画像所属用户标识。
    user_id TEXT NOT NULL,
    -- 画像所属 Agent 标识。
    agent_id TEXT NOT NULL,
    -- 最近生成该画像的会话标识，用于审计来源。
    session_id TEXT NOT NULL,
    -- 最近生成该画像的任务标识，空字符串表示未绑定任务。
    task_id TEXT NOT NULL DEFAULT '',
    -- 长期画像的 Markdown 正文。
    content TEXT NOT NULL,
    -- 画像内容版本，从 1 开始递增。
    version INTEGER NOT NULL,
    -- 最后更新时间，Unix epoch 毫秒。
    updated_at INTEGER NOT NULL,
    UNIQUE (team_id, user_id, agent_id)
);

CREATE TABLE memory_pipeline_jobs (
    -- L1-L3 后台加工任务唯一标识。
    id TEXT PRIMARY KEY,
    -- 触发任务的 L0 对话轮次标识。
    turn_id TEXT NOT NULL,
    -- 任务所属团队标识，用于租户隔离。
    team_id TEXT NOT NULL,
    -- 任务所属用户标识。
    user_id TEXT NOT NULL,
    -- 执行记忆加工的 Agent 标识。
    agent_id TEXT NOT NULL,
    -- 触发任务的会话标识。
    session_id TEXT NOT NULL,
    -- 可选任务标识，空字符串表示未绑定具体任务。
    task_id TEXT NOT NULL DEFAULT '',
    -- 当前加工阶段枚举：L1、L2、L3 或 DONE。
    stage TEXT NOT NULL,
    -- 当前任务状态枚举：PENDING、RUNNING、FAILED 或 COMPLETED。
    status TEXT NOT NULL,
    -- 已开始执行当前及历史阶段的累计次数。
    attempts INTEGER NOT NULL,
    -- 最近一次失败原因；无失败时为空字符串。
    error TEXT NOT NULL DEFAULT '',
    -- 状态最后更新时间，Unix epoch 毫秒。
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (turn_id) REFERENCES memory_turns(id) ON DELETE CASCADE
);
