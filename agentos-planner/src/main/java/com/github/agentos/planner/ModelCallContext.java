package com.github.agentos.planner;

import java.time.Instant;
import java.util.Objects;

/** 一次模型调用生命周期共享的不可变上下文。 */
public record ModelCallContext(PlanningRequest request, Instant startedAt) {

    /** 校验模型请求和开始时间。 */
    public ModelCallContext {
        request = Objects.requireNonNull(request, "request must not be null");
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
