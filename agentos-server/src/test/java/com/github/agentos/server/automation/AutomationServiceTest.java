package com.github.agentos.server.automation;

import com.github.agentos.server.automation.AutomationModels.WorkspaceContext;
import com.github.agentos.server.automation.AutomationModels.WorkspaceContextFile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动化任务工作区上下文注入的预算裁剪测试。
 *
 * <p>回归（线上死循环）：早期实现里服务端把工作区正文总长限制在 28 KB，而
 * {@code ConversationCompactor} 的压缩预算仅 24 KB。种子消息本身就超出
 * 压缩预算，迫使压缩器每轮丢弃最新工具观察，模型失忆并重复发起同一工具调用。
 * 这里约束 {@code validateContext}/{@code buildRunInput} 拼出的种子消息
 * 永远 ≤ 24 KB。</p>
 */
class AutomationServiceTest {

    private static final int COMPACTION_BUDGET = 24 * 1024;

    @Test
    void validateContextCapsTotalFileCharsAtCompressionBudget() throws Exception {
        // 注入 16 个超大文件：旧实现会保留 28 KB 正文的子集；现在必须截到 ~23 KB
        // 以保证 head + 通知拼出后总长 ≤ 24 KB（压缩预算）。
        List<WorkspaceContextFile> oversized = IntStream.range(0, 16)
                .mapToObj(i -> new WorkspaceContextFile(
                        "f" + i + ".txt",
                        "F".repeat(8 * 1024),
                        false,
                        i == 0))
                .map(file -> new WorkspaceContextFile(
                        file.path(),
                        file.content(),
                        file.truncated(),
                        file.mentioned()))
                .toList();

        WorkspaceContext context = invokeValidateContext(
                new WorkspaceContext("agent-platform",
                        List.of("ReadMe.md", "pom.xml"),
                        oversized,
                        false));

        // 总正文长 ≤ 23 KB（head/通知预留约 1 KB）。
        int totalChars = context.files().stream().mapToInt(f -> f.content().length()).sum();
        assertThat(totalChars).isLessThanOrEqualTo(23 * 1024);
        // 总量超出服务端上限时 truncated 必须传播，buildRunInput 会据此追加通知。
        assertThat(context.truncated()).isTrue();
        // 至少有一个文件被丢弃或被截断。
        long truncatedOrDropped = context.files().stream()
                .filter(f -> f.truncated() || f.content().length() < 8 * 1024)
                .count();
        assertThat(truncatedOrDropped).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void buildRunInputStaysWithinCompactionBudgetWhenAllFilesAreOversized() throws Exception {
        // 极端场景：所有文件都填满正文预算。最坏情况下也必须 ≤ 24 KB。
        List<WorkspaceContextFile> oversized = IntStream.range(0, 8)
                .mapToObj(i -> new WorkspaceContextFile(
                        "f" + i + ".txt",
                        "X".repeat(12 * 1024),
                        true,
                        i == 0))
                .toList();

        WorkspaceContext validated = invokeValidateContext(
                new WorkspaceContext("agent-platform",
                        List.of("ReadMe.md"),
                        oversized,
                        false));
        String runInput = invokeBuildRunInput("生成日报", validated);

        assertThat(runInput.length()).isLessThanOrEqualTo(COMPACTION_BUDGET);
        assertThat(runInput).startsWith("生成日报");
        // 因服务端截断触发 truncated=true，buildRunInput 必须追加通知。
        assertThat(runInput).contains("需要完整内容时调用 file_read");
    }

    @Test
    void buildRunInputOmitsTruncationNoticeWhenAllFilesFit() throws Exception {
        // 正常场景：小项目所有文件都能塞下，不应出现截断通知。
        List<WorkspaceContextFile> small = List.of(
                new WorkspaceContextFile("ReadMe.md", "# Demo", false, true),
                new WorkspaceContextFile("pom.xml", "<pom/>", false, false));
        WorkspaceContext validated = invokeValidateContext(
                new WorkspaceContext("agent-platform",
                        List.of("ReadMe.md", "pom.xml"),
                        small,
                        false));

        String runInput = invokeBuildRunInput("分析项目", validated);

        assertThat(runInput.length()).isLessThanOrEqualTo(COMPACTION_BUDGET);
        assertThat(runInput).doesNotContain("超出上下文预算已被裁剪或丢弃");
    }

    private static WorkspaceContext invokeValidateContext(WorkspaceContext input) throws Exception {
        AutomationService service = newService();
        Method m = AutomationService.class.getDeclaredMethod(
                "validateContext", WorkspaceContext.class, String.class);
        m.setAccessible(true);
        return (WorkspaceContext) m.invoke(service, input, "agent-platform");
    }

    private static String invokeBuildRunInput(String prompt, WorkspaceContext context) throws Exception {
        Method m = AutomationService.class.getDeclaredMethod(
                "buildRunInput", String.class, WorkspaceContext.class);
        m.setAccessible(true);
        return (String) m.invoke(null, prompt, context);
    }

    private static AutomationService newService() {
        return new AutomationService(
                new AutomationStore("memory", null, null, null, null),
                new AutomationScheduleCalculator(),
                null,
                null,
                null,
                null);
    }
}
