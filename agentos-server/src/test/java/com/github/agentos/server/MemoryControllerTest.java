package com.github.agentos.server;

import com.github.agentos.memory.CompletedTurn;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.server.controller.MemoryController;
import com.github.agentos.server.handler.AgentExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MemoryControllerTest {

    @Test
    void returnsReadOnlyLayeredMemorySnapshot() throws Exception {
        MemoryScope scope = MemoryScope.defaultScope("main-agent", "session-1");

        try (MemoryService memoryService = MemoryService.inMemory()) {
            memoryService.capture(CompletedTurn.success(
                    scope,
                    "I prefer Java.",
                    "Preference recorded.",
                    List.of("Preference recorded.")));
            if (!memoryService.awaitIdle(Duration.ofSeconds(2))) {
                throw new AssertionError("memory pipeline did not become idle");
            }
            MockMvc mockMvc = standaloneSetup(new MemoryController(memoryService))
                    .setControllerAdvice(new AgentExceptionHandler())
                    .build();

            mockMvc.perform(get("/api/memories").param("sessionId", "session-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope.teamId").value("default-team"))
                    .andExpect(jsonPath("$.scope.userId").value("default-user"))
                    .andExpect(jsonPath("$.scope.agentId").value("main-agent"))
                    .andExpect(jsonPath("$.scope.sessionId").value("session-1"))
                    .andExpect(jsonPath("$.counts.l0").value(1))
                    .andExpect(jsonPath("$.counts.l1").value(1))
                    .andExpect(jsonPath("$.counts.l2").value(1))
                    .andExpect(jsonPath("$.counts.l3").value(1))
                    .andExpect(jsonPath("$.recentTurns[0].userInput").value("I prefer Java."))
                    .andExpect(jsonPath("$.atomicMemories[0].content").value("I prefer Java."))
                    .andExpect(jsonPath("$.scenarios[0].name").value("agent:main-agent"))
                    .andExpect(jsonPath("$.profile.content").isNotEmpty());
        }
    }

    @Test
    void rejectsRecentLimitOutsideTheSupportedRange() throws Exception {
        try (MemoryService memoryService = MemoryService.inMemory()) {
            MockMvc mockMvc = standaloneSetup(new MemoryController(memoryService))
                    .setControllerAdvice(new AgentExceptionHandler())
                    .build();

            mockMvc.perform(get("/api/memories")
                            .param("sessionId", "session-1")
                            .param("recentLimit", "101"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("recentLimit must be between 1 and 100"));
        }
    }
}
