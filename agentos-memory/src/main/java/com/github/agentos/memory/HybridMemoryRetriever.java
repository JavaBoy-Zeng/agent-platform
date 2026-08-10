package com.github.agentos.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 使用 BM25、向量余弦相似度和 RRF 完成 L1 混合召回。
 */
public final class HybridMemoryRetriever {

    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;
    private static final int RRF_K = 60;
    private final MemoryStore store;
    private final MemoryEmbedding embedding;

    public HybridMemoryRetriever(MemoryStore store, MemoryEmbedding embedding) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.embedding = Objects.requireNonNull(embedding, "embedding must not be null");
    }

    public List<MemorySearchHit> search(MemoryQuery query) {
        List<AtomicMemory> documents = store.listAtomic(query.scope()).stream()
                .filter(memory -> query.types().isEmpty() || query.types().contains(memory.type()))
                .toList();
        if (documents.isEmpty()) return List.of();

        List<Scored> keyword = rankBm25(query.text(), documents);
        List<Scored> vector = rankVector(query.text(), documents);
        Map<String, Fused> fused = new HashMap<>();
        addRanks(fused, keyword, "bm25");
        addRanks(fused, vector, "vector");

        List<Fused> ranked = fused.values().stream()
                .sorted(Comparator.comparingDouble(Fused::score).reversed())
                .limit(query.limit())
                .toList();
        if (ranked.isEmpty()) return List.of();

        double max = ranked.getFirst().score();
        return ranked.stream()
                .map(item -> new MemorySearchHit(
                        item.memory(),
                        max == 0 ? 0 : item.score() / max,
                        item.sources().size() > 1 ? "hybrid-rrf" : item.sources().keySet().iterator().next()))
                .toList();
    }

    private static List<Scored> rankBm25(String queryText, List<AtomicMemory> documents) {
        List<String> queryTokens = TextAnalyzer.tokenize(queryText);
        if (queryTokens.isEmpty()) return List.of();
        Map<String, List<String>> tokensById = documents.stream().collect(Collectors.toMap(
                AtomicMemory::id,
                memory -> TextAnalyzer.tokenize(memory.content()),
                (left, right) -> left,
                LinkedHashMap::new));
        double averageLength = tokensById.values().stream().mapToInt(List::size).average().orElse(1.0);
        Map<String, Long> documentFrequency = queryTokens.stream().distinct().collect(Collectors.toMap(
                Function.identity(),
                token -> tokensById.values().stream().filter(tokens -> tokens.contains(token)).count()));

        List<Scored> result = new ArrayList<>();
        for (AtomicMemory memory : documents) {
            List<String> tokens = tokensById.get(memory.id());
            Map<String, Long> termFrequency = tokens.stream().collect(Collectors.groupingBy(
                    Function.identity(), Collectors.counting()));
            double score = 0;
            for (String token : queryTokens.stream().distinct().toList()) {
                long frequency = termFrequency.getOrDefault(token, 0L);
                if (frequency == 0) continue;
                long docsWithTerm = documentFrequency.getOrDefault(token, 0L);
                double idf = Math.log(1 + (documents.size() - docsWithTerm + 0.5) / (docsWithTerm + 0.5));
                double denominator = frequency + BM25_K1 * (1 - BM25_B + BM25_B * tokens.size() / averageLength);
                score += idf * frequency * (BM25_K1 + 1) / denominator;
            }
            if (score > 0) result.add(new Scored(memory, score));
        }
        result.sort(Comparator.comparingDouble(Scored::score).reversed());
        return result;
    }

    private List<Scored> rankVector(String queryText, List<AtomicMemory> documents) {
        double[] queryVector = embedding.embed(queryText);
        List<Scored> result = new ArrayList<>();
        for (AtomicMemory memory : documents) {
            double score = cosine(queryVector, embedding.embed(memory.content()));
            if (score > 0) result.add(new Scored(memory, score));
        }
        result.sort(Comparator.comparingDouble(Scored::score).reversed());
        return result;
    }

    private static void addRanks(Map<String, Fused> fused, List<Scored> ranked, String source) {
        for (int index = 0; index < ranked.size(); index++) {
            Scored item = ranked.get(index);
            double rrf = 1.0 / (RRF_K + index + 1);
            double qualityBoost = 0.8 + item.memory().confidence() * 0.1 + item.memory().priority() * 0.01;
            Fused current = fused.computeIfAbsent(item.memory().id(), ignored -> new Fused(item.memory()));
            current.add(source, rrf * qualityBoost);
        }
    }

    private static double cosine(double[] left, double[] right) {
        if (left.length != right.length) throw new IllegalArgumentException("embedding dimensions do not match");
        double dot = 0;
        double a = 0;
        double b = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            a += left[index] * left[index];
            b += right[index] * right[index];
        }
        return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
    }

    private record Scored(AtomicMemory memory, double score) {
    }

    private static final class Fused {
        private final AtomicMemory memory;
        private final Map<String, Double> sources = new LinkedHashMap<>();

        private Fused(AtomicMemory memory) {
            this.memory = memory;
        }

        void add(String source, double value) {
            sources.merge(source, value, Double::sum);
        }

        AtomicMemory memory() {
            return memory;
        }

        Map<String, Double> sources() {
            return sources;
        }

        double score() {
            return sources.values().stream().mapToDouble(Double::doubleValue).sum();
        }
    }
}
