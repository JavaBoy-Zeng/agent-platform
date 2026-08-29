package com.github.agentos.server.persistence.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface UsageMapper extends BaseMapper<PersistenceRows.UsageRow> {

    @Insert("""
            INSERT INTO session_usage
                (session_id, model_calls, prompt_tokens, completion_tokens, updated_at)
            VALUES (#{sessionId}, 1, #{promptTokens}, #{completionTokens}, CURRENT_TIMESTAMP)
            ON CONFLICT (session_id) DO UPDATE SET
                model_calls = session_usage.model_calls + 1,
                prompt_tokens = session_usage.prompt_tokens + EXCLUDED.prompt_tokens,
                completion_tokens = session_usage.completion_tokens + EXCLUDED.completion_tokens,
                updated_at = CURRENT_TIMESTAMP
            """)
    void increment(
            @Param("sessionId") String sessionId,
            @Param("promptTokens") long promptTokens,
            @Param("completionTokens") long completionTokens);
}
