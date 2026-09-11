package com.github.agentos.server;

import com.github.agentos.memory.CompletedTurn;
import com.github.agentos.memory.AtomicMemory;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.kernel.InMemorySessionService;
import com.github.agentos.server.controller.MemoryController;
import com.github.agentos.server.handler.AgentExceptionHandler;
import com.github.agentos.server.security.RequestIdentity;
import com.github.agentos.server.security.SessionAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MemoryControllerTest {

    @Test
    void returnsReadOnlyLayeredMemorySnapshot() throws Exception {
        MemoryScope scope = MemoryScope.defaultScope("plan-execute-agent", "session-1");

        try (MemoryService memoryService = MemoryService.inMemory()) {
            memoryService.capture(CompletedTurn.success(
                    scope,
                    "I prefer Java.",
                    "Preference recorded.",
                    List.of("Preference recorded.")));
            if (!memoryService.awaitIdle(Duration.ofSeconds(2))) {
                throw new AssertionError("memory pipeline did not become idle");
            }
            MockMvc mockMvc = standaloneSetup(new MemoryController(
                            memoryService, TestSessionAuthorizations.owned("session-1")))
                    .setControllerAdvice(new AgentExceptionHandler())
                    .build();

            mockMvc.perform(get("/api/memories").param("sessionId", "session-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope.teamId").value("default-team"))
                    .andExpect(jsonPath("$.scope.userId").value("default-user"))
                    .andExpect(jsonPath("$.scope.agentId").value("plan-execute-agent"))
                    .andExpect(jsonPath("$.scope.sessionId").value("session-1"))
                    .andExpect(jsonPath("$.counts.l0").value(1))
                    .andExpect(jsonPath("$.counts.l1").value(1))
                    .andExpect(jsonPath("$.counts.l2").value(1))
                    .andExpect(jsonPath("$.counts.l3").value(1))
                    .andExpect(jsonPath("$.recentTurns[0].userInput").value("I prefer Java."))
                    .andExpect(jsonPath("$.atomicMemories[0].content").value("I prefer Java."))
                    .andExpect(jsonPath("$.scenarios[0].name").value("agent:plan-execute-agent"))
                    .andExpect(jsonPath("$.profile.content").isNotEmpty());
        }
    }

    @Test
    void rejectsRecentLimitOutsideTheSupportedRange() throws Exception {
        try (MemoryService memoryService = MemoryService.inMemory()) {
            MockMvc mockMvc = standaloneSetup(new MemoryController(
                            memoryService, TestSessionAuthorizations.owned("session-1")))
                    .setControllerAdvice(new AgentExceptionHandler())
                    .build();

            mockMvc.perform(get("/api/memories")
                            .param("sessionId", "session-1")
                            .param("recentLimit", "101"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("recentLimit must be between 1 and 100"));
        }
    }

    @Test
    void managesMemoryLifecycleWithRequestBoundIdentity() throws Exception {
        try (MemoryService memoryService = MemoryService.inMemory()) {
            MockMvc mockMvc = standaloneSetup(new MemoryController(
                            memoryService, TestSessionAuthorizations.ownedBy("user-a")))
                    .setControllerAdvice(new AgentExceptionHandler())
                    .build();
            RequestIdentity identity = new RequestIdentity("team-a", "user-a", Set.of());

            String created = mockMvc.perform(post("/api/memories/facts")
                            .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, identity)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"agentId":"agent","sessionId":"session","taskId":"task",
                                     "content":"Use Java 21","ttlSeconds":3600}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.scope.teamId").value("team-a"))
                    .andExpect(jsonPath("$.scope.userId").value("user-a"))
                    .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            String memoryId = created.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");

            mockMvc.perform(patch("/api/memories/atomic/{id}", memoryId)
                            .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, identity)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"content":"Use Java 22","expiresAt":"2030-01-01T00:00:00Z"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").value("Use Java 22"))
                    .andExpect(jsonPath("$.sourceTurnIds.length()").value(2));

            mockMvc.perform(put("/api/memories/atomic/{id}/ttl", memoryId)
                            .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, identity)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ttlSeconds\":7200}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.expiresAt").isNotEmpty());

            String replacement = mockMvc.perform(post(
                                    "/api/memories/atomic/{id}/supersede", memoryId)
                            .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, identity)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"Use Java 23\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").value("Use Java 23"))
                    .andReturn().getResponse().getContentAsString();
            String replacementId = replacement.replaceAll(
                    "(?s).*\"id\":\"([^\"]+)\".*", "$1");
            AtomicMemory superseded = memoryService.findAtomic(memoryId);
            assertThat(superseded.supersededById()).isEqualTo(replacementId);

            mockMvc.perform(post("/api/memories/atomic/{id}/invalidate", replacementId)
                            .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, identity))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("INVALIDATED"));

            mockMvc.perform(delete("/api/memories/atomic/{id}", replacementId)
                            .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, identity))
                    .andExpect(status().isNoContent());
            assertThat(memoryService.findAtomic(replacementId)).isNull();
        }
    }

    @Test
    void deniesCrossIdentityAccessUnlessMemoryAdmin() throws Exception {
        MemoryScope ownerScope = new MemoryScope("team-a", "user-a", "agent", "session", "");
        try (MemoryService memoryService = MemoryService.inMemory()) {
            AtomicMemory memory = memoryService.rememberFact(ownerScope, "private fact");
            InMemorySessionService sessions = new InMemorySessionService();
            sessions.getOrCreate("session", "user-a");
            sessions.getOrCreate("admin-session", "admin");
            MockMvc mockMvc = standaloneSetup(new MemoryController(
                            memoryService, new SessionAuthorization(sessions)))
                    .setControllerAdvice(new AgentExceptionHandler())
                    .build();

            RequestIdentity stranger = new RequestIdentity("team-a", "user-b", Set.of());
            mockMvc.perform(delete("/api/memories/atomic/{id}", memory.id())
                            .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, stranger))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/memories")
                            .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, stranger)
                            .param("teamId", "team-a")
                            .param("userId", "user-a")
                            .param("agentId", "agent")
                            .param("sessionId", "session"))
                    .andExpect(status().isNotFound());

            RequestIdentity admin = new RequestIdentity(
                    "operations", "admin", Set.of(RequestIdentity.MEMORY_ADMIN));
            mockMvc.perform(get("/api/memories")
                            .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, admin)
                            .param("teamId", "team-a")
                            .param("userId", "user-a")
                            .param("agentId", "agent")
                            .param("sessionId", "admin-session"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.counts.l0").value(0))
                    .andExpect(jsonPath("$.counts.l1").value(1));
        }
    }
}
