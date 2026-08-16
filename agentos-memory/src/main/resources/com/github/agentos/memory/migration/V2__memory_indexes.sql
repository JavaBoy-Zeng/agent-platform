CREATE INDEX idx_memory_turns_scope_completed
    ON memory_turns(team_id, user_id, agent_id, session_id, task_id, completed_at DESC);

CREATE INDEX idx_memory_atomic_scope_updated
    ON memory_atomic(team_id, user_id, agent_id, task_id, updated_at DESC);

CREATE INDEX idx_memory_scenarios_scope_updated
    ON memory_scenarios(team_id, user_id, agent_id, task_id, updated_at DESC);

CREATE INDEX idx_memory_pipeline_recovery
    ON memory_pipeline_jobs(status, attempts, updated_at ASC);
