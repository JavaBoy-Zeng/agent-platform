package com.github.agentos.agent.registry;

import com.github.agentos.agent.Agent;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内线程安全 AgentRegistry，默认可只注册 main-agent。 */
public final class InMemoryAgentRegistry implements AgentRegistry {

    private final ConcurrentMap<String, Agent> agents = new ConcurrentHashMap<>();

    /** 创建空注册表。 */
    public InMemoryAgentRegistry() {
    }

    /** 创建并注册给定 Agent 集合。 */
    public InMemoryAgentRegistry(Collection<? extends Agent> agents) {
        Objects.requireNonNull(agents, "agents must not be null").forEach(this::register);
    }

    /** 原子注册 Agent，拒绝覆盖同标识实例。 */
    @Override
    public void register(Agent agent) {
        Objects.requireNonNull(agent, "agent must not be null");
        Agent previous = agents.putIfAbsent(agent.id(), agent);
        if (previous != null) {
            throw new IllegalArgumentException("agent already registered: " + agent.id());
        }
    }

    /** 返回已注册 Agent。 */
    @Override
    public Optional<Agent> find(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }
}
