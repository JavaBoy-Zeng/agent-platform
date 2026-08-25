package com.github.agentos.server.config;

import com.github.agentos.tool.skill.ClasspathSkillSource;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import com.github.agentos.tool.skill.LoadSkillTool;
import com.github.agentos.tool.skill.LocalSkillSource;
import com.github.agentos.tool.skill.SkillRegistry;
import com.github.agentos.tool.skill.SkillSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 技能体系 Spring 装配。
 *
 * <p>技能按来源优先级加载：本地目录（{@code agentos.skills.root}）
 * 先于 classpath 资源（{@code agentos.skills.classpath-resources}），
 * 同名技能先注册者生效，因此本地目录可覆盖内置技能。技能正文不常驻
 * 提示词，由 {@code load_skill} 工具按需注入对话上下文。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "agentos.skills.enabled", havingValue = "true", matchIfMissing = true)
public class SkillConfiguration {

    /**
     * 聚合本地目录与 classpath 资源创建技能注册表。
     *
     * @param root              本地技能根目录；目录不存在时该来源为空
     * @param classpathResources classpath 技能资源路径（逗号分隔）
     * @return 已加载完成的技能注册表
     */
    @Bean
    SkillRegistry skillRegistry(
            FileAccessPolicy fileAccessPolicy,
            @Value("${agentos.skills.root:.agentos/skills}") String root,
            @Value("${agentos.skills.classpath-resources:skills/report-writing/SKILL.md,skills/code-review/SKILL.md}")
            String classpathResources) {
        List<String> resources = java.util.Arrays.stream(classpathResources.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .toList();
        List<SkillSource> sources = List.of(
                new LocalSkillSource(fileAccessPolicy.authorizeRead(Path.of(root))),
                new ClasspathSkillSource(resources));
        try {
            return new SkillRegistry().loadFrom(sources);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load skills", exception);
        }
    }

    /** 创建 load_skill 工具，把注册表中的技能按需注入对话上下文。 */
    @Bean
    LoadSkillTool loadSkillTool(SkillRegistry skillRegistry) {
        return new LoadSkillTool(skillRegistry);
    }
}
