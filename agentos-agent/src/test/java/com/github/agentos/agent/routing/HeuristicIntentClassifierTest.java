package com.github.agentos.agent.routing;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 启发式意图分类器单元测试。 */
class HeuristicIntentClassifierTest {

    private static final String MESSAGE = "收到，已记录。";

    private final HeuristicIntentClassifier classifier =
            new HeuristicIntentClassifier(
                    16, 64, "simple-qa-agent",
                    "你好！有什么我可以帮你的吗？",
                    "好的。",
                    "不客气！有需要随时告诉我。",
                    "晚安，祝你好梦。");

    // ---------- 寒暄/确认白名单命中 ----------

    @Test
    void shortCircuitsChineseGreeting() {
        assertThat(classifier.classify(
                new AgentRequest("s1", "你好", Map.of()),
                InvocationContext.of("main-agent")).isShortCircuit()).isTrue();
    }

    @Test
    void shortCircuitsChineseGreetingWithPunctuation() {
        assertThat(classifier.classify(
                new AgentRequest("s1", "你好！", Map.of()),
                InvocationContext.of("main-agent")).isShortCircuit()).isTrue();
    }

    @Test
    void shortCircuitsEnglishGreetingCaseInsensitive() {
        assertThat(classifier.classify(
                new AgentRequest("s1", "HI", Map.of()),
                InvocationContext.of("main-agent")).isShortCircuit()).isTrue();
    }

    @Test
    void shortCircuitsMorningGreeting() {
        assertThat(classifier.classify(
                new AgentRequest("s1", "早上好", Map.of()),
                InvocationContext.of("main-agent")).isShortCircuit()).isTrue();
    }

    @Test
    void shortCircuitsAcknowledgement() {
        assertThat(classifier.classify(
                new AgentRequest("s1", "好的", Map.of()),
                InvocationContext.of("main-agent")).isShortCircuit()).isTrue();
    }

    @Test
    void shortCircuitsEnglishAcknowledgement() {
        assertThat(classifier.classify(
                new AgentRequest("s1", "thanks", Map.of()),
                InvocationContext.of("main-agent")).isShortCircuit()).isTrue();
    }

    @Test
    void shortCircuitsThankYou() {
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "thank you", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.isShortCircuit()).isTrue();
        assertThat(result.intent()).isEqualTo("trivial-thanks");
        assertThat(result.directAnswer()).isEqualTo("不客气！有需要随时告诉我。");
    }

    @Test
    void usesGreetingSpecificReply() {
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "你好", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.intent()).isEqualTo("trivial-greeting");
        assertThat(result.directAnswer()).isEqualTo("你好！有什么我可以帮你的吗？");
    }

    @Test
    void routesContextualYesToSimpleQaWhenHistoryExists() {
        for (String input : new String[] {"yes", "no", "好的", "ok"}) {
            IntentClassification result = classifier.classify(
                    new AgentRequest("s1", input, Map.of(
                            "conversationHistory", "助手：要继续执行吗？")),
                    InvocationContext.of("main-agent"));

            assertThat(result.isShortCircuit()).as(input).isFalse();
            assertThat(result.agentId()).as(input).isEqualTo("simple-qa-agent");
        }
    }

    @Test
    void shortCircuitsGreetingWithSurroundingWhitespace() {
        assertThat(classifier.classify(
                new AgentRequest("s1", "   你好  ", Map.of()),
                InvocationContext.of("main-agent")).isShortCircuit()).isTrue();
    }

    // ---------- 不应当被短路的真实场景（回归用例） ----------

    @Test
    void doesNotShortCircuitWeatherQuestion() {
        // 中文事实查询 — 必须落到 MainAgent，让 LLM 调 WeatherTool
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "重庆多少度？", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.isShortCircuit()).isFalse();
        assertThat(result.hasAgentTarget()).isFalse();
        assertThat(result.intent()).isEqualTo("heuristic-fallback");
    }

    @Test
    void doesNotShortCircuitEnglishWeatherQuestion() {
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "what's the weather", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.isShortCircuit()).isFalse();
        assertThat(result.hasAgentTarget()).isFalse();
    }

    @Test
    void doesNotShortCircuitStockQuestion() {
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "今天上证多少点", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.isShortCircuit()).isFalse();
        assertThat(result.hasAgentTarget()).isFalse();
    }

    @Test
    void routesArithmeticToSimpleQa() {
        // 纯算术不需要工具，短文本且无任务信号词 → 派发到简单问答 Agent
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "3+5 等于几", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.isShortCircuit()).isFalse();
        assertThat(result.hasAgentTarget()).isTrue();
        assertThat(result.agentId()).isEqualTo("simple-qa-agent");
        assertThat(result.intent()).isEqualTo("simple-qa");
    }

    @Test
    void doesNotShortCircuitGreetingWithToolAppended() {
        // "你好，帮我搜索..." 这种把寒暄和任务拼接的输入，必须落到 fallback
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "你好帮我搜索", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.isShortCircuit()).isFalse();
        assertThat(result.hasAgentTarget()).isFalse();
    }

    @Test
    void doesNotShortCircuitGreetingLikeWordAsQuestion() {
        // "嗨，你是谁" 不应被短路（去除标点后是 "嗨你是谁"，不在白名单）
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "嗨你是谁", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.isShortCircuit()).isFalse();
    }

    // ---------- 简单问答分级 ----------

    @Test
    void routesShortConceptQuestionToSimpleQa() {
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "什么是 JVM？", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.hasAgentTarget()).isTrue();
        assertThat(result.agentId()).isEqualTo("simple-qa-agent");
        assertThat(result.intent()).isEqualTo("simple-qa");
    }

    @Test
    void routesComparisonQuestionToSimpleQa() {
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "java 和 go 有什么区别", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.agentId()).isEqualTo("simple-qa-agent");
    }

    @Test
    void routesEnglishConceptQuestionToSimpleQa() {
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "what is recursion?", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.agentId()).isEqualTo("simple-qa-agent");
    }

    @Test
    void routesGreetingLikeQuestionToSimpleQa() {
        // "嗨你是谁" 不是寒暄白名单，但属于简单问答
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "嗨你是谁", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.agentId()).isEqualTo("simple-qa-agent");
    }

    @Test
    void taskSignalWordsForceFallbackEvenWhenShort() {
        // 任务信号词优先于简单问答：需要工具/实时信息的短请求必须走 MainAgent
        for (String input : new String[] {
                "查天气", "读一下这个文件", "创建一个文件", "git 提交一下",
                "search for cats", "run the tests", "open the config",
                "现在几点", "最新新闻", "黄金价格", "北京现在堵车吗",
                "what is the latest news", "current gold price",
                // 日期/时间类：直答模型不知道当前日期，必须走 current_date 工具
                "今天几号", "今天是哪年哪月哪日", "今天是星期几", "what's today's date"}) {
            IntentClassification result = classifier.classify(
                    new AgentRequest("s1", input, Map.of()),
                    InvocationContext.of("main-agent"));

            assertThat(result.hasAgentTarget())
                    .as("input '%s' must fall back to MainAgent", input)
                    .isFalse();
        }
    }

    /**
     * 把上一轮结论落盘的请求（“写成 md 文档放到桌面”）短且无动词式信号词，
     * 曾被判为简单问答走无工具直答，模型只能回答“我无法保存文件”。
     * 产出落盘类信号必须进入复杂任务链路。
     */
    @Test
    void artifactRequestsFallBackInsteadOfSimpleQa() {
        for (String input : new String[] {
                "是否可以写成md文档 给我放在桌面",
                "帮我整理成一份文档",
                "导出成 markdown",
                "存到桌面",
                "把结论放在桌面上",
                "export it to my desktop"}) {
            IntentClassification result = classifier.classify(
                    new AgentRequest("s1", input, Map.of()),
                    InvocationContext.of("main-agent"));

            assertThat(result.hasAgentTarget())
                    .as("input '%s' must fall back to the tool-capable path", input)
                    .isFalse();
        }
    }

    @Test
    void englishSignalsUseWordBoundaries() {
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "what is a thread", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.agentId()).isEqualTo("simple-qa-agent");
    }

    @Test
    void longQuestionFallsBackInsteadOfSimpleQa() {
        IntentClassification result = classifier.classify(
                new AgentRequest("s1", "什么是 JVM".repeat(20), Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.hasAgentTarget()).isFalse();
        assertThat(result.intent()).isEqualTo("heuristic-fallback");
    }

    @Test
    void zeroSimpleQaMaxCharsDisablesSimpleQa() {
        HeuristicIntentClassifier disabled = new HeuristicIntentClassifier(16, MESSAGE);
        IntentClassification result = disabled.classify(
                new AgentRequest("s1", "什么是 JVM", Map.of()),
                InvocationContext.of("main-agent"));

        assertThat(result.hasAgentTarget()).isFalse();
        assertThat(result.intent()).isEqualTo("heuristic-fallback");
    }

    // ---------- 边界与配置 ----------

    @Test
    void zeroMaxCharsDisablesShortCircuit() {
        HeuristicIntentClassifier strict = new HeuristicIntentClassifier(0, MESSAGE);
        assertThat(strict.classify(
                new AgentRequest("s1", "你好", Map.of()),
                InvocationContext.of("main-agent")).isShortCircuit()).isFalse();
    }

    @Test
    void inputExceedingMaxCharsNotShortCircuited() {
        HeuristicIntentClassifier tight = new HeuristicIntentClassifier(4, MESSAGE);
        // "thank you" = 9 chars, 即使在白名单也因长度被拒
        assertThat(tight.classify(
                new AgentRequest("s1", "thank you", Map.of()),
                InvocationContext.of("main-agent")).isShortCircuit()).isFalse();
    }

    @Test
    void blankMessageRejected() {
        assertThatThrownBy(() -> new HeuristicIntentClassifier(16, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeMaxCharsRejected() {
        assertThatThrownBy(() -> new HeuristicIntentClassifier(-1, MESSAGE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
