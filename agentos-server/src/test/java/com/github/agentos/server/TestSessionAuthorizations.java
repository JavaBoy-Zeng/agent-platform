package com.github.agentos.server;

import com.github.agentos.kernel.InMemorySessionService;
import com.github.agentos.server.security.SessionAuthorization;

/** 为 MVC 单元测试创建默认账号拥有的会话授权上下文。 */
final class TestSessionAuthorizations {

    private TestSessionAuthorizations() {
    }

    static SessionAuthorization owned(String... sessionIds) {
        return ownedBy("default-user", sessionIds);
    }

    static SessionAuthorization ownedBy(String userId, String... sessionIds) {
        InMemorySessionService sessions = new InMemorySessionService();
        for (String sessionId : sessionIds) {
            sessions.getOrCreate(sessionId, userId);
        }
        return new SessionAuthorization(sessions);
    }
}
