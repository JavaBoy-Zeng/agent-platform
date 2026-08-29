package com.github.agentos.server.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;

/** 仅在 PostgreSQL 持久化模式下注册 MyBatis-Plus Mapper。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${agentos.persistence.mode:postgresql}'.toLowerCase() matches 'postgres(ql)?'")
@MapperScan("com.github.agentos.server.persistence.mybatis")
public class PostgresqlMapperConfiguration {
}
