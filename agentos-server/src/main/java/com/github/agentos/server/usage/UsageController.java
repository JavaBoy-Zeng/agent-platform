package com.github.agentos.server.usage;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 模型 token 用量查询接口。 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageRecorder recorder;

    /** 创建用量查询接口。 */
    public UsageController(UsageRecorder recorder) {
        this.recorder = recorder;
    }

    /** 返回指定会话的累计模型用量。 */
    @GetMapping("/{sessionId}")
    public Map<String, Object> sessionUsage(@PathVariable String sessionId) {
        UsageRecorder.SessionUsage usage = recorder.summary(sessionId);
        return Map.of(
                "sessionId", sessionId,
                "modelCalls", usage.modelCalls(),
                "promptTokens", usage.promptTokens(),
                "completionTokens", usage.completionTokens(),
                "totalTokens", usage.totalTokens());
    }
}
