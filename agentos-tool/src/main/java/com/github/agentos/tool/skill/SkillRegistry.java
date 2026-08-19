package com.github.agentos.tool.skill;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 以技能标识为键的线程安全注册表。
 *
 * <p>注册表聚合多个 {@link SkillSource} 的解析结果；多来源出现同名技能时
 * 先注册者生效，后注册者被跳过，装配层可借此实现“本地目录覆盖 classpath”
 * 的优先级策略。</p>
 */
public final class SkillRegistry {

    private final ConcurrentMap<String, AgentSkill> skills = new ConcurrentHashMap<>();

    /**
     * 注册一项技能。
     *
     * @param skill 待注册技能
     * @throws IllegalArgumentException 当同名技能已存在时抛出
     */
    public void register(AgentSkill skill) {
        AgentSkill existing = skills.putIfAbsent(skill.id(), skill);
        if (existing != null) {
            throw new IllegalArgumentException("skill already registered: " + skill.id());
        }
    }

    /**
     * 注册技能；同名技能已存在时跳过。
     *
     * @param skill 待注册技能
     * @return 注册成功返回 {@code true}，因重名被跳过返回 {@code false}
     */
    public boolean registerIfAbsent(AgentSkill skill) {
        return skills.putIfAbsent(skill.id(), skill) == null;
    }

    /**
     * 按标识查找技能。
     *
     * @param id 技能标识
     * @return 找到时返回包含技能的 {@link Optional}，否则返回空值
     */
    public Optional<AgentSkill> find(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    /**
     * 获取当前已注册技能的只读快照（按注册顺序）。
     *
     * @return 技能列表
     */
    public List<AgentSkill> all() {
        return List.copyOf(skills.values());
    }

    /**
     * 依序聚合多个来源的技能；单个来源加载失败会向上抛出，
     * 重名技能按先到先得保留。
     *
     * @param sources 技能来源列表
     * @return 本注册表（便于链式调用）
     * @throws IOException 当任一来源读取失败时抛出
     */
    public SkillRegistry loadFrom(List<SkillSource> sources) throws IOException {
        for (SkillSource source : sources) {
            for (AgentSkill skill : source.load()) {
                registerIfAbsent(skill);
            }
        }
        return this;
    }
}
