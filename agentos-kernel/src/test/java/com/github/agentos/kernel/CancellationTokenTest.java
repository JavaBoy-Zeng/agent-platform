package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 协作式取消令牌单元测试。 */
class CancellationTokenTest {

    @Test
    void startsNotCancelled() {
        CancellationToken token = CancellationToken.notCancelled();

        assertThat(token.isCancelled()).isFalse();
        assertThat(token.reason()).isEmpty();
        assertThatCode(token::throwIfCancelled).doesNotThrowAnyException();
    }

    @Test
    void cancelRecordsReasonAndThrows() {
        CancellationToken token = CancellationToken.notCancelled();

        token.cancel("user requested");

        assertThat(token.isCancelled()).isTrue();
        assertThat(token.reason()).contains("user requested");
        assertThatThrownBy(token::throwIfCancelled)
                .isInstanceOf(CancellationException.class)
                .hasMessage("user requested");
    }

    @Test
    void firstReasonWinsOnRepeatedCancel() {
        CancellationToken token = CancellationToken.notCancelled();

        token.cancel("first");
        token.cancel("second");

        assertThat(token.reason()).contains("first");
    }

    @Test
    void blankReasonFallsBackToDefault() {
        CancellationToken token = CancellationToken.notCancelled();

        token.cancel("  ");

        assertThat(token.reason()).contains("cancelled");
    }

    @Test
    void cancellationIsVisibleAcrossThreads() throws InterruptedException {
        CancellationToken token = CancellationToken.notCancelled();
        Thread worker = new Thread(() -> {
            while (!token.isCancelled()) {
                Thread.yield();
            }
        });
        worker.start();
        token.cancel("stop");
        worker.join(5_000);
        assertThat(worker.isAlive()).isFalse();
    }
}
