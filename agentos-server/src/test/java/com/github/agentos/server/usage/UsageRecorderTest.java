package com.github.agentos.server.usage;

import com.github.agentos.kernel.ModelUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话用量记账器单元测试。 */
class UsageRecorderTest {

    private final UsageRecorder recorder = new UsageRecorder(new UsageStore.InMemoryUsageStore());

    @Test
    void accumulatesUsagePerSession() {
        recorder.onModelUsage("s1", new ModelUsage("model-a", 100, 50));
        recorder.onModelUsage("s1", new ModelUsage("model-a", 200, 150));
        recorder.onModelUsage("s2", new ModelUsage("model-b", 10, 5));

        UsageStore.SessionUsage s1 = recorder.summary("s1");
        assertThat(s1.modelCalls()).isEqualTo(2);
        assertThat(s1.promptTokens()).isEqualTo(300);
        assertThat(s1.completionTokens()).isEqualTo(200);
        assertThat(s1.totalTokens()).isEqualTo(500);
        assertThat(recorder.summary("s2").totalTokens()).isEqualTo(15);
    }

    @Test
    void unknownSessionReportsZeroUsage() {
        UsageStore.SessionUsage usage = recorder.summary("missing");

        assertThat(usage.modelCalls()).isZero();
        assertThat(usage.totalTokens()).isZero();
    }

    @Test
    void ignoresBlankSessionId() {
        recorder.onModelUsage(null, new ModelUsage("m", 1, 1));
        recorder.onModelUsage(" ", new ModelUsage("m", 1, 1));

        assertThat(recorder.summary(" ")).extracting(
                UsageStore.SessionUsage::modelCalls).isEqualTo(0L);
    }
}
