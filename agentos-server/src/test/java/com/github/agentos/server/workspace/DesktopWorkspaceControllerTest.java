package com.github.agentos.server.workspace;

import com.github.agentos.kernel.InMemorySessionService;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import com.github.agentos.server.security.*;
import com.github.agentos.server.handler.AgentExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DesktopWorkspaceControllerTest {
    @Test void endpointBindsOwnedSessionAndRejectsUsersWithoutWorkspaceRole() throws Exception {
        var sessions = new InMemorySessionService();
        var bridge = new DesktopWorkspaceBridge(sessions, new AgentRunTaskRegistry());
        var users = mock(UserStore.class);
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        var mvc = MockMvcBuilders.standaloneSetup(new DesktopWorkspaceController(bridge,
                new SessionAuthorization(sessions), users)).setControllerAdvice(new AgentExceptionHandler()).build();
        String body = """
                {"workspaceId":"w1","root":"/real/project","branch":"main","device":"Mac","osName":"macos"}
                """;
        mvc.perform(post("/api/desktop-workspaces/task/connect").contentType(MediaType.APPLICATION_JSON).content(body)
                .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, new RequestIdentity("t", "alice", Set.of("USER"))))
                .andExpect(status().isForbidden());
        var registered = mvc.perform(post("/api/desktop-workspaces/task/connect").contentType(MediaType.APPLICATION_JSON).content(body)
                .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, new RequestIdentity("t", "alice", Set.of("WORKSPACE"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runtime.workspaceId").value("w1"))
                .andExpect(jsonPath("$.runtime.root").value("/real/project"))
                .andExpect(jsonPath("$.runtime.workspaceSessionId").value("task"))
                .andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(registered, "$.token");
        mvc.perform(post("/api/desktop-workspaces/task/poll").header("X-Workspace-Token", token)
                .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, new RequestIdentity("t", "bob", Set.of("ADMIN"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/desktop-workspaces/task/poll").header("X-Workspace-Token", token)
                .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, new RequestIdentity("t", "alice", Set.of("WORKSPACE"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.running").value(false));
        mvc.perform(post("/api/desktop-workspaces/task/poll").header("X-Workspace-Token", "invalid")
                .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, new RequestIdentity("t", "alice", Set.of("WORKSPACE"))))
                .andExpect(status().isBadRequest());
        when(users.findByUsername("alice")).thenReturn(Optional.of(new UserAccount("alice", "hash", Set.of("USER"), java.time.Instant.now())));
        mvc.perform(post("/api/desktop-workspaces/task/poll").header("X-Workspace-Token", token)
                .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE, new RequestIdentity("t", "alice", Set.of("WORKSPACE"))))
                .andExpect(status().isForbidden());
    }
}
