package com.github.agentos.server.workspace;

import com.github.agentos.server.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/desktop-workspaces/{sessionId}")
public final class DesktopWorkspaceController {
    private final DesktopWorkspaceBridge bridge;
    private final SessionAuthorization authorization;
    private final UserStore users;
    public DesktopWorkspaceController(DesktopWorkspaceBridge bridge, SessionAuthorization authorization, UserStore users) {
        this.bridge = bridge; this.authorization = authorization; this.users = users;
    }
    private String user(HttpServletRequest request) {
        RequestIdentity identity = RequestIdentity.from(request);
        var roles = users.findByUsername(identity.userId()).map(UserAccount::roles).orElse(identity.roles());
        if (!roles.contains("WORKSPACE") && !roles.contains("ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "本机工作区需要 WORKSPACE 或 ADMIN 角色");
        return identity.userId();
    }
    @PostMapping("/connect")
    public DesktopWorkspaceBridge.Registration connect(@PathVariable String sessionId,
            @RequestBody DesktopWorkspaceBridge.Binding binding, HttpServletRequest request) {
        String owner = user(request);
        authorization.claim(sessionId, request);
        return bridge.register(sessionId, owner, binding);
    }
    @PostMapping("/poll")
    public DesktopWorkspaceBridge.Poll poll(@PathVariable String sessionId,
            @RequestHeader("X-Workspace-Token") String token, HttpServletRequest request) {
        authorization.requireOwned(sessionId, request);
        return bridge.poll(sessionId, user(request), token);
    }
    @PostMapping("/results/{id}")
    public void complete(@PathVariable String sessionId, @PathVariable String id,
            @RequestHeader("X-Workspace-Token") String token,
            @RequestBody DesktopWorkspaceBridge.Completion result, HttpServletRequest request) {
        authorization.requireOwned(sessionId, request);
        bridge.complete(sessionId, user(request), token, id, result);
    }
    @PostMapping("/disconnect")
    public void disconnect(@PathVariable String sessionId,
            @RequestHeader("X-Workspace-Token") String token, HttpServletRequest request) {
        authorization.requireOwned(sessionId, request);
        bridge.disconnect(sessionId, user(request), token);
    }
}
