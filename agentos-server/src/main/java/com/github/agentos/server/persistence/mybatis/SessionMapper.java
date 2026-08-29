package com.github.agentos.server.persistence.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

public interface SessionMapper extends BaseMapper<PersistenceRows.SessionRow> {

    @Insert("""
            INSERT INTO agent_sessions
                (session_id, user_id, state_payload, created_at, last_active_at)
            VALUES (#{sessionId}, #{userId}, #{statePayload}, #{createdAt}, #{lastActiveAt})
            ON CONFLICT (session_id) DO NOTHING
            """)
    void insertIfAbsent(PersistenceRows.SessionRow row);

    @Select("""
            SELECT session_id, user_id, state_payload, created_at, last_active_at
            FROM agent_sessions WHERE session_id = #{sessionId} FOR UPDATE
            """)
    PersistenceRows.SessionRow selectForUpdate(@Param("sessionId") String sessionId);
}
