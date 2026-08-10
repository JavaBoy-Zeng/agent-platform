package com.github.agentos.planner;

import java.util.List;
import java.util.Map;

/**
 * 模型客户端解析出的结构化计划。
 *
 * <p>该类型属于模型适配层的传输模型，允许暂时承载不完整内容。它不能直接执行，
 * 必须由 {@link LlmTaskPlanner} 转换为领域 {@link Plan} 并通过 {@link PlanValidator} 校验。</p>
 *
 * @param objective 模型理解的任务目标
 * @param steps 模型生成的有序步骤
 */
public record ModelPlan(String objective, List<Step> steps) {

    /**
     * 复制模型步骤列表，避免外部修改已经返回的响应。
     */
    public ModelPlan {
        steps = steps == null ? null : List.copyOf(steps);
    }

    /**
     * 模型输出中的一个工具调用步骤。
     *
     * @param id 步骤标识
     * @param description 步骤说明
     * @param toolName 工具名称
     * @param arguments 工具参数
     */
    public record Step(
            String id,
            String description,
            String toolName,
            Map<String, Object> arguments) {

        /**
         * 复制工具参数，模型未返回参数对象时保留为空值并交由规划器报告错误。
         */
        public Step {
            arguments = arguments == null ? null : Map.copyOf(arguments);
        }
    }
}
