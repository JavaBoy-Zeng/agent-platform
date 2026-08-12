package com.github.agentos.agent;

import java.util.Optional;

/** Agent 注册与查找协议；本阶段不负责调度或 Agent 间转交。 */
public interface AgentRegistry {

    /** 注册一个 Agent，重复标识由实现拒绝。 */
    void register(Agent agent);

    /** 按稳定标识查找 Agent。 */
    Optional<Agent> find(String agentId);
}
