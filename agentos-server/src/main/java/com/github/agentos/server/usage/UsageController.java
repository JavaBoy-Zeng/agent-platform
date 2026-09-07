package com.github.agentos.server.usage;

import com.github.agentos.server.security.SessionAuthorization;
import jakarta.servlet.http.HttpServletRequest;
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
    private final SessionAuthorization authorization;

    /** 创建用量查询接口。 */
    public UsageController(UsageRecorder recorder, SessionAuthorization authorization) {
        this.recorder = recorder;
        this.authorization = authorization;
    }

    /** 返回指定会话的累计模型用量。 */
    @GetMapping("/{sessionId}")
    public Map<String, Object> sessionUsage(
            @PathVariable String sessionId, HttpServletRequest request) {
        authorization.requireOwned(sessionId, request);
        UsageStore.SessionUsage usage = recorder.summary(sessionId);
        return Map.of(
                "sessionId", sessionId,
                "modelCalls", usage.modelCalls(),
                "promptTokens", usage.promptTokens(),
                "completionTokens", usage.completionTokens(),
                "totalTokens", usage.totalTokens());
    }
}
