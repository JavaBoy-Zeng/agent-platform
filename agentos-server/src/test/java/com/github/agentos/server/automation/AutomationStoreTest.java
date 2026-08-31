package com.github.agentos.server.automation;

import com.github.agentos.server.automation.AutomationModels.AutomationExecution;
import com.github.agentos.server.automation.AutomationModels.AutomationTask;
import com.github.agentos.server.automation.AutomationModels.ExecutionStatus;
import com.github.agentos.server.automation.AutomationModels.PeriodMode;
import com.github.agentos.server.automation.AutomationModels.PeriodUnit;
import com.github.agentos.server.automation.AutomationModels.TriggerDefinition;
import com.github.agentos.server.automation.AutomationModels.TriggerSource;
import com.github.agentos.server.automation.AutomationModels.TriggerType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationStoreTest {

    @Test
    void memoryStoreIsolatesOwnersAndClaimsQueuedExecutionOnce() {
        AutomationStore store = new AutomationStore("memory", null, null, null, null);
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        TriggerDefinition trigger = new TriggerDefinition(TriggerType.PERIOD, PeriodMode.BASIC,
                PeriodUnit.DAILY, "09:00", null, null, null, null, null, "Asia/Shanghai");
        AutomationTask task = new AutomationTask("task-1", "team", "alice", "日报", "生成日报",
                "main-agent", "model", "RISK_BASED", "desktop", "workspace", "project",
                trigger, true, now.plusSeconds(60), null, null, now, now, 0);
        store.save(task);

        assertThat(store.tasks("team", "alice")).containsExactly(task);
        assertThat(store.tasks("team", "bob")).isEmpty();

        AutomationExecution execution = new AutomationExecution("exec-1", "task-1", "team",
                "alice", "日报", TriggerSource.SCHEDULED, ExecutionStatus.QUEUED,
                "task-1:scheduled", now, "desktop", "workspace", "project", null, null,
                "", "", "", null, null, "", "", now, now, 0);
        assertThat(store.createExecution(execution)).isTrue();
        assertThat(store.createExecution(execution)).isFalse();
        assertThat(store.claim("desktop", "team", "alice", now, now.plusSeconds(60)))
                .get().extracting(AutomationExecution::status).isEqualTo(ExecutionStatus.CLAIMED);
        assertThat(store.claim("desktop", "team", "alice", now, now.plusSeconds(60))).isEmpty();
        assertThat(store.claim("desktop", "team", "bob", now, now.plusSeconds(60))).isEmpty();
    }
}
