package com.github.agentos.memory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 判断新候选是否明确替代已有记忆。 */
@FunctionalInterface
public interface MemoryConflictResolver {

    boolean supersedes(AtomicMemory existing, MemoryModel.AtomicCandidate candidate);

    /** 保守规则：只有显式更新语义，或相近陈述中的关键数字发生变化时才判定替代。 */
    static MemoryConflictResolver conservative() {
        Pattern replacement = Pattern.compile(
                "改为|升级到|现在(?:是|使用)?|不再|取消|instead|changed? to|upgraded? to|now uses?",
                Pattern.CASE_INSENSITIVE);
        Pattern number = Pattern.compile("(?<![\\p{L}])\\d+(?:\\.\\d+)*(?![\\p{L}])");
        return (existing, candidate) -> {
            if (existing.type() != candidate.type()) return false;
            double similarity = TextAnalyzer.jaccard(existing.content(), candidate.content());
            if (similarity < 0.25) return false;
            if (replacement.matcher(candidate.content()).find()) return true;
            Matcher left = number.matcher(existing.content());
            Matcher right = number.matcher(candidate.content());
            return left.find() && right.find() && !left.group().equals(right.group());
        };
    }
}
