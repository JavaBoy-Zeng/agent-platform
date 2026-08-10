package com.github.agentos.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 零配置环境使用的确定性记忆模型。 */
public final class RuleBasedMemoryModel implements MemoryModel {

    private static final Pattern PREFERENCE = Pattern.compile(
            "偏好|喜欢|习惯|希望|倾向|prefer|like|would like", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRAINT = Pattern.compile(
            "必须|不要|不能|禁止|只允许|务必|约束|must|never|do not|only", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECISION = Pattern.compile(
            "决定|采用|选择|确定|改为|方案是|will use|decided|choose", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT = Pattern.compile(
            "已经|曾经|之前|上次|完成了|发生|故障|already|previously|last time", Pattern.CASE_INSENSITIVE);
    private static final Pattern FACT = Pattern.compile(
            "我是|名称是|项目是|使用|技术栈|负责|属于|版本|is |uses? |based on", Pattern.CASE_INSENSITIVE);

    @Override
    public List<AtomicCandidate> extractAtomic(CompletedTurn turn) {
        List<AtomicCandidate> result = new ArrayList<>();
        for (String sentence : splitSentences(turn.userInput())) {
            if (sentence.length() < 2 || sentence.length() > 2_000) continue;
            Classification classification = classify(sentence);
            if (looksLikePureQuestion(sentence) && classification.type() == MemoryType.EXPERIENCE) continue;
            result.add(new AtomicCandidate(
                    classification.type(), sentence, classification.confidence(), classification.priority()));
        }
        return List.copyOf(result);
    }

    @Override
    public String synthesizeScenario(MemoryScope scope, List<AtomicMemory> memories) {
        if (memories.isEmpty()) return "";
        Map<MemoryType, List<AtomicMemory>> grouped = new EnumMap<>(MemoryType.class);
        memories.stream()
                .sorted(Comparator.comparingInt(AtomicMemory::priority).reversed()
                        .thenComparing(AtomicMemory::updatedAt, Comparator.reverseOrder()))
                .limit(50)
                .forEach(memory -> grouped.computeIfAbsent(memory.type(), ignored -> new ArrayList<>()).add(memory));

        StringBuilder result = new StringBuilder();
        result.append("# 场景记忆\n\n");
        result.append("- Agent: ").append(scope.agentId()).append('\n');
        if (!scope.taskId().isEmpty()) result.append("- Task: ").append(scope.taskId()).append('\n');
        for (MemoryType type : MemoryType.values()) {
            List<AtomicMemory> values = grouped.get(type);
            if (values == null || values.isEmpty()) continue;
            result.append("\n## ").append(label(type)).append('\n');
            values.stream().limit(10).forEach(memory -> result.append("- ").append(memory.content()).append('\n'));
        }
        return result.toString().trim();
    }

    @Override
    public String synthesizeProfile(
            MemoryScope scope,
            List<AtomicMemory> memories,
            List<ScenarioMemory> scenarios) {
        List<AtomicMemory> stable = memories.stream()
                .filter(memory -> memory.type() == MemoryType.PREFERENCE
                        || memory.type() == MemoryType.CONSTRAINT
                        || memory.type() == MemoryType.DECISION
                        || memory.type() == MemoryType.FACT
                        || memory.type() == MemoryType.PERSONA)
                .sorted(Comparator.comparingInt(AtomicMemory::priority).reversed()
                        .thenComparing(AtomicMemory::confidence, Comparator.reverseOrder()))
                .limit(30)
                .toList();
        if (stable.isEmpty() && scenarios.isEmpty()) return "";

        StringBuilder result = new StringBuilder("# 核心画像\n\n");
        result.append("- User: ").append(scope.userId()).append('\n');
        result.append("- Agent: ").append(scope.agentId()).append('\n');
        if (!stable.isEmpty()) {
            result.append("\n## 稳定认知\n");
            for (AtomicMemory memory : stable) {
                result.append("- [").append(label(memory.type())).append("] ")
                        .append(memory.content()).append('\n');
            }
        }
        if (!scenarios.isEmpty()) {
            result.append("\n## 可用场景\n");
            scenarios.stream().limit(10)
                    .forEach(scenario -> result.append("- ").append(scenario.name()).append('\n'));
        }
        return result.toString().trim();
    }

    private static List<String> splitSentences(String text) {
        String normalized = text.replace('\r', '\n').replaceAll("[ \\t]+", " ").trim();
        if (normalized.isEmpty()) return List.of();
        String[] parts = normalized.split("(?<=[。！？!?；;\\n])");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String cleaned = part.trim().replaceAll("[\\n]+$", "");
            if (!cleaned.isEmpty()) result.add(cleaned);
        }
        return result;
    }

    private static Classification classify(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (PREFERENCE.matcher(normalized).find()) return new Classification(MemoryType.PREFERENCE, 0.92, 8);
        if (CONSTRAINT.matcher(normalized).find()) return new Classification(MemoryType.CONSTRAINT, 0.94, 9);
        if (DECISION.matcher(normalized).find()) return new Classification(MemoryType.DECISION, 0.90, 8);
        if (EVENT.matcher(normalized).find()) return new Classification(MemoryType.EVENT, 0.78, 6);
        if (FACT.matcher(normalized).find()) return new Classification(MemoryType.FACT, 0.76, 6);
        return new Classification(MemoryType.EXPERIENCE, 0.55, 3);
    }

    private static boolean looksLikePureQuestion(String text) {
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("?") || normalized.endsWith("？")
                || normalized.startsWith("为什么") || normalized.startsWith("怎么")
                || normalized.startsWith("what ") || normalized.startsWith("how ");
    }

    private static String label(MemoryType type) {
        return switch (type) {
            case FACT -> "事实";
            case PREFERENCE -> "偏好";
            case CONSTRAINT -> "约束";
            case DECISION -> "决策";
            case EVENT -> "事件";
            case EXPERIENCE -> "经验";
            case PERSONA -> "画像";
        };
    }

    private record Classification(MemoryType type, double confidence, int priority) {
    }
}
