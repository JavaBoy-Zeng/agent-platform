package com.github.agentos.planner;

/**
 * 面向任务规划场景的大语言模型客户端协议。
 *
 * <p>该接口不绑定任何模型厂商或 SDK。具体适配器负责构造提示词、请求模型的结构化输出，
 * 并将厂商响应解析为 {@link ModelPlan}。规划模块只处理统一模型。</p>
 */
@FunctionalInterface
public interface ModelClient {

    /**
     * 根据完整规划请求生成结构化计划。
     *
     * @param request 用户输入、运行上下文、记忆、工具定义和规划约束
     * @return 模型生成的结构化计划
     */
    ModelPlan generatePlan(PlanningRequest request);
}
