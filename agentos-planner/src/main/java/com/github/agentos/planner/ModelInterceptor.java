package com.github.agentos.planner;

/** 模型调用前后、失败时的有序生命周期扩展点。 */
public interface ModelInterceptor {

    /** 调用模型前可审计或调整请求。 */
    default PlanningRequest beforeCall(
            PlanningRequest request, ModelCallContext context) {
        return request;
    }

    /** 调用成功后可记录指标或调整统一响应。 */
    default ModelPlan afterCall(ModelPlan response, ModelCallContext context) {
        return response;
    }

    /**
     * 调用失败后处理异常；返回非空计划表示已提供 fallback，返回空表示继续传播异常。
     */
    default ModelPlan onError(Throwable error, ModelCallContext context) {
        return null;
    }
}
