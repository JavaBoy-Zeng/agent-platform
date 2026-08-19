package com.github.agentos.tool.skill;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 classpath 显式资源路径加载技能的来源。
 *
 * <p>classpath 目录无法在 JAR 内可靠枚举，因此来源在构造时声明全部技能
 * 资源路径（如 {@code skills/java-debugging/SKILL.md}），逐个按与
 * {@link LocalSkillSource} 相同的 frontmatter 格式解析。</p>
 */
public final class ClasspathSkillSource implements SkillSource {

    private static final String FRONTMATTER_DELIMITER = "---";

    private final ClassLoader classLoader;
    private final List<String> resources;
    private final ObjectMapper yaml;

    /**
     * 使用当前线程上下文类加载器创建来源。
     *
     * @param resources 技能资源路径列表
     */
    public ClasspathSkillSource(List<String> resources) {
        this(Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader()
                : ClasspathSkillSource.class.getClassLoader(),
                resources);
    }

    /**
     * 使用指定类加载器创建来源。
     *
     * @param classLoader 用于定位资源的类加载器
     * @param resources   技能资源路径列表
     */
    public ClasspathSkillSource(ClassLoader classLoader, List<String> resources) {
        this.classLoader = classLoader;
        this.resources = List.copyOf(resources);
        this.yaml = new ObjectMapper(YAMLFactory.builder().build());
    }

    @Override
    public List<AgentSkill> load() throws IOException {
        List<AgentSkill> skills = new ArrayList<>();
        for (String resource : resources) {
            skills.add(parse(resource));
        }
        return List.copyOf(skills);
    }

    private AgentSkill parse(String resource) throws IOException {
        String content;
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("skill resource not found on classpath: " + resource);
            }
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] lines = content.split("\n", -1);
        if (lines.length == 0 || !FRONTMATTER_DELIMITER.equals(lines[0].strip())) {
            throw new IllegalStateException(
                    "skill resource must start with '---' frontmatter: " + resource);
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (FRONTMATTER_DELIMITER.equals(lines[i].strip())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalStateException("skill frontmatter is not closed: " + resource);
        }
        Map<String, Object> fields = readYaml(String.join("\n",
                Arrays.copyOfRange(lines, 1, end)), resource);
        String name = text(fields.remove("name"), "name", resource);
        String description = text(fields.remove("description"), "description", resource);
        String instructions = String.join("\n",
                Arrays.copyOfRange(lines, end + 1, lines.length)).strip();
        if (instructions.isBlank()) {
            throw new IllegalStateException("skill instructions must not be blank: " + resource);
        }
        return new AgentSkill(name, name, description, instructions, fields, resource);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(String body, String resource) {
        if (body.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> raw = yaml.readValue(body, Map.class);
            Map<String, Object> fields = new LinkedHashMap<>();
            raw.forEach((key, value) -> fields.put(String.valueOf(key), value));
            return fields;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "skill frontmatter is not valid YAML: " + resource, exception);
        }
    }

    private static String text(Object value, String field, String resource) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException(
                    "skill frontmatter must declare non-blank '" + field + "': " + resource);
        }
        return String.valueOf(value).strip();
    }
}
