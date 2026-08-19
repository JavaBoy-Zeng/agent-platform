package com.github.agentos.server.config;

import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.eval.ToolTrajectoryEvaluator;
import com.github.agentos.server.eval.EvaluationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评估体系 Spring 装配。
 *
 * <p>评估器本身无状态，可直接单例复用；服务从事件存储回放
 * Invocation 轨迹后交给评估器比对。</p>
 */
@Configuration(proxyBeanMethods = false)
public class EvaluationConfiguration {

    /** 创建轨迹评估器。 */
    @Bean
    ToolTrajectoryEvaluator toolTrajectoryEvaluator() {
        return new ToolTrajectoryEvaluator();
    }

    /** 创建面向 REST 接口的评估服务。 */
    @Bean
    EvaluationService evaluationService(
            AgentEventStore agentEventStore, ToolTrajectoryEvaluator evaluator) {
        return new EvaluationService(agentEventStore, evaluator);
    }
}
