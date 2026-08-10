package com.github.agentos.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AgentOS 服务端的 Spring Boot 启动入口。
 */
@SpringBootApplication
public class AgentOsApplication {

    /**
     * 创建 Spring Boot 应用配置实例。
     */
    public AgentOsApplication() {
    }

    /**
     * 启动 AgentOS Web 服务和所有已配置的 Agent 组件。
     *
     * @param args Spring Boot 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentOsApplication.class, args);
    }
}
