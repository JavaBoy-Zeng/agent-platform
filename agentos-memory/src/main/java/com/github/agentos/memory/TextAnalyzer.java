package com.github.agentos.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 无第三方依赖的中英文分词和相似度工具。 */
final class TextAnalyzer {

    private TextAnalyzer() {
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        String previousHan = null;

        int[] codePoints = text.toLowerCase(Locale.ROOT).codePoints().toArray();
        for (int codePoint : codePoints) {
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushWord(word, tokens);
                String current = new String(Character.toChars(codePoint));
                tokens.add(current);
                if (previousHan != null) tokens.add(previousHan + current);
                previousHan = current;
            } else if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(codePoint);
                previousHan = null;
            } else {
                flushWord(word, tokens);
                previousHan = null;
            }
        }
        flushWord(word, tokens);
        return tokens;
    }

    static String normalize(String text) {
        return String.join(" ", tokenize(text));
    }

    static double jaccard(String left, String right) {
        Set<String> a = new HashSet<>(tokenize(left));
        Set<String> b = new HashSet<>(tokenize(right));
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private static void flushWord(StringBuilder word, List<String> tokens) {
        if (word.length() == 0) return;
        tokens.add(word.toString());
        word.setLength(0);
    }
}
