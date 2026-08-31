package com.github.agentos.server.persistence.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

public interface AutomationExecutionMapper extends BaseMapper<PersistenceRows.AutomationExecutionRow> {

    @Update("""
            UPDATE automation_executions
            SET status = 'CLAIMED', claimed_at = #{claimedAt},
                lease_expires_at = #{leaseExpiresAt}, updated_at = #{claimedAt},
                version = version + 1
            WHERE execution_id = #{executionId} AND status = 'QUEUED'
            """)
    int claim(@Param("executionId") String executionId,
              @Param("claimedAt") Instant claimedAt,
              @Param("leaseExpiresAt") Instant leaseExpiresAt);
}
