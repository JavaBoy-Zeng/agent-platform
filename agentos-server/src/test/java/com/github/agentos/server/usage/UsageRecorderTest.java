package com.github.agentos.server.usage;

import com.github.agentos.kernel.ModelUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话用量记账器单元测试。 */
class UsageRecorderTest {

    private final UsageRecorder recorder = new UsageRecorder(new UsageStore.InMemoryUsageStore());

    @Test
    void nestedUsageScopeChargesRootAndRestoresAfterFailure() {
        try (var outer = com.github.agentos.kernel.ModelUsageScope.open("root")) {
            recorder.onModelUsage("child", new ModelUsage("test", 10, 5));
            try {
                try (var inner = com.github.agentos.kernel.ModelUsageScope.open("other")) {
                    recorder.onModelUsage("grandchild", new ModelUsage("test", 20, 5));
                    throw new IllegalStateException("model failed");
                }
            } catch (IllegalStateException expected) {
                recorder.onModelUsage("child", new ModelUsage("test", 30, 5));
            }
        }
        recorder.onModelUsage("standalone", new ModelUsage("test", 1, 1));
        assertThat(recorder.summary("root").totalTokens()).isEqualTo(50);
        assertThat(recorder.summary("other").totalTokens()).isEqualTo(25);
        assertThat(recorder.summary("child").totalTokens()).isZero();
        assertThat(recorder.summary("standalone").totalTokens()).isEqualTo(2);
    }

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
