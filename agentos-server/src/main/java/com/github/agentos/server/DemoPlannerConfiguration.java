package com.github.agentos.server;

import com.github.agentos.planner.DemoTaskPlanner;
import com.github.agentos.planner.TaskPlanner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 本地演示和端到端测试使用的规划器装配。
 *
 * <p>该配置只在 {@code demo} Profile 激活时生效，不会在生产配置中注册固定计划实现。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoPlannerConfiguration {

    /**
     * 创建演示规划器配置。
     */
    public DemoPlannerConfiguration() {
    }

    /**
     * 创建单步骤回显任务规划器。
     *
     * @return 演示任务规划器
     */
    @Bean
    TaskPlanner demoTaskPlanner() {
        return new DemoTaskPlanner();
    }
}
