package com.github.agentos.agent.specialist;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedMarkdownDocumentTest {

    @Test
    void keepsOnlyFinalDocumentAndRepairsGenericTitle() {
        String raw = """
                <think>这里是不能写入文件的内部推理。</think>
                ```markdown
                # 顶部引用块

                > 第一版草稿

                ## 一、问题背景

                旧正文
                ```
                我还需要继续调整这份文档。
                </mm:think>
                ```markdown
                # 顶部引用块

                > 最终摘要

                ## 一、问题背景

                最终正文
                ```
                """;

        GeneratedMarkdownDocument document = GeneratedMarkdownDocument.from(
                "请生成并保存一份关于抖音视频 Agent Skill过多 4招提升命中率.md", raw);

        assertThat(document.title()).isEqualTo("抖音视频 Agent Skill过多 4招提升命中率");
        assertThat(document.fileName()).isEqualTo(
                "抖音视频-Agent-Skill过多-4招提升命中率.md");
        assertThat(document.content())
                .startsWith("# 抖音视频 Agent Skill过多 4招提升命中率\n")
                .contains("最终摘要", "最终正文")
                .doesNotContain("<think>", "</mm:think>", "```markdown", "第一版草稿", "继续调整");
        assertThat(occurrences(document.content(), "## 一、问题背景")).isEqualTo(1);
    }

    @Test
    void preservesMeaningfulHeadingAndDoesNotTreatCodeCommentAsAnotherDocument() {
        String raw = """
                # Agent Skill 精简指南

                示例：

                ```bash
                # 安装依赖
                npm install
                ```
                """;

        GeneratedMarkdownDocument document = GeneratedMarkdownDocument.from("请写一份文档", raw);

        assertThat(document.title()).isEqualTo("Agent Skill 精简指南");
        assertThat(document.fileName()).isEqualTo("Agent-Skill-精简指南.md");
        assertThat(document.content()).contains("```bash", "# 安装依赖", "npm install", "```");
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
