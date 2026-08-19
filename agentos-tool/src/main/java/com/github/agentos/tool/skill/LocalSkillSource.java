package com.github.agentos.tool.skill;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 从本地文件系统目录发现并解析 SKILL.md 的技能来源。
 *
 * <p>支持两种布局：{@code <root>/<目录>/SKILL.md}（技能目录）与
 * {@code <root>/<文件名>.md}（单文件技能）。技能文件由 YAML frontmatter
 * （首个 {@code ---} 围栏）声明 {@code name} 与 {@code description}，
 * 围栏之后的 Markdown 正文即技能指令。</p>
 */
public final class LocalSkillSource implements SkillSource {

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String FRONTMATTER_DELIMITER = "---";

    private final Path root;
    private final ObjectMapper yaml;

    /**
     * 创建本地技能来源。
     *
     * @param root 技能根目录；目录不存在时 {@link #load()} 返回空列表
     */
    public LocalSkillSource(Path root) {
        this.root = root;
        this.yaml = new ObjectMapper(YAMLFactory.builder().build());
    }

    @Override
    public List<AgentSkill> load() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<AgentSkill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                Path file = dir.resolve(SKILL_FILE_NAME);
                if (Files.isRegularFile(file)) {
                    skills.add(parse(file));
                }
            });
        }
        try (Stream<Path> files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.toLowerCase().endsWith(".md") && !name.equalsIgnoreCase(SKILL_FILE_NAME);
                    })
                    .forEach(file -> skills.add(parse(file)));
        }
        return List.copyOf(skills);
    }

    private AgentSkill parse(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "failed to read skill file: " + file, exception);
        }
        ParsedSkill parsed = parseFrontmatter(content, file.toString());
        return new AgentSkill(
                parsed.name(), parsed.name(), parsed.description(),
                parsed.instructions(), parsed.metadata(), file.toString());
    }

    private ParsedSkill parseFrontmatter(String content, String file) {
        String[] lines = content.split("\n", -1);
        if (lines.length == 0 || !FRONTMATTER_DELIMITER.equals(lines[0].strip())) {
            throw new IllegalStateException("skill file must start with '---' frontmatter: " + file);
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (FRONTMATTER_DELIMITER.equals(lines[i].strip())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalStateException("skill frontmatter is not closed: " + file);
        }
        Map<String, Object> fields = readYaml(String.join("\n",
                java.util.Arrays.copyOfRange(lines, 1, end)), file);
        String name = text(fields.remove("name"), "name", file);
        String description = text(fields.remove("description"), "description", file);
        String instructions = String.join("\n",
                java.util.Arrays.copyOfRange(lines, end + 1, lines.length)).strip();
        if (instructions.isBlank()) {
            throw new IllegalStateException("skill instructions must not be blank: " + file);
        }
        return new ParsedSkill(name, description, instructions, fields);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(String body, String file) {
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
                    "skill frontmatter is not valid YAML: " + file, exception);
        }
    }

    private static String text(Object value, String field, String file) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException(
                    "skill frontmatter must declare non-blank '" + field + "': " + file);
        }
        return String.valueOf(value).strip();
    }

    private record ParsedSkill(
            String name, String description, String instructions, Map<String, Object> metadata) {
    }
}
