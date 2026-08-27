package com.github.agentos.agent.routing;

/** 专家 Agent 在产生任何副作用前对路由任务的接单结果。 */
public record RouteAcceptance(boolean accepted, String reason) {

    public RouteAcceptance {
        reason = reason == null ? "" : reason.trim();
        if (!accepted && reason.isBlank()) {
            throw new IllegalArgumentException("rejected route must contain reason");
        }
    }

    public static RouteAcceptance accept() {
        return new RouteAcceptance(true, "");
    }

    public static RouteAcceptance reject(String reason) {
        return new RouteAcceptance(false, reason);
    }
}
