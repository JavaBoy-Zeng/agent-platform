package com.github.agentos.memory;

import java.util.List;

/** 原子记忆的向量持久化与近邻查询端口。 */
public interface MemoryVectorIndex {

    /** 为最新内容版本建立或刷新索引。 */
    void index(AtomicMemory memory);

    /** 在已经完成元数据过滤的候选集合中执行向量排序。 */
    List<Match> search(String queryText, List<AtomicMemory> candidates, int limit);

    record Match(AtomicMemory memory, double score) {
    }
}
