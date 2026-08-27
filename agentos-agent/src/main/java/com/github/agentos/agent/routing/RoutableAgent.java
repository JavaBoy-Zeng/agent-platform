package com.github.agentos.agent.routing;

import com.github.agentos.agent.Agent;
import com.github.agentos.kernel.AgentRequest;

import java.util.Set;

/** 可向 Supervisor 声明能力并在执行前拒绝错误路由的 Agent。 */
public interface RoutableAgent extends Agent {

    Set<AgentCapability> capabilities();

    /** 默认仅校验能力集合；实现可追加作用域等语义校验。 */
    default RouteAcceptance accepts(
            AgentRequest request, SupervisorRouteDecision decision) {
        if (!capabilities().containsAll(decision.requiredCapabilities())) {
            return RouteAcceptance.reject("required capabilities are not supported");
        }
        return RouteAcceptance.accept();
    }
}
