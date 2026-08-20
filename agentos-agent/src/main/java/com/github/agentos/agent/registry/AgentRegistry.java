package com.github.agentos.agent.registry;

import com.github.agentos.agent.Agent;

import java.util.List;
import java.util.Optional;

/** Agent 注册与查找协议；本阶段不负责调度或 Agent 间转交。 */
public interface AgentRegistry {

    /** 注册一个 Agent，重复标识由实现拒绝。 */
    void register(Agent agent);

    /** 按稳定标识查找 Agent。 */
    Optional<Agent> find(String agentId);

    /**
     * 返回当前已注册 Agent 的只读快照。
     *
     * <p>供管理面板展示 Agent 拓扑，顺序由实现决定，调用方不应依赖。</p>
     */
    List<Agent> all();
}
