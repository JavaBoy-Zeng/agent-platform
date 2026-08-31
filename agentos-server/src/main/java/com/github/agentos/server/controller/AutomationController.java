package com.github.agentos.server.controller;

import com.github.agentos.server.automation.AutomationModels.AutomationClient;
import com.github.agentos.server.automation.AutomationModels.AutomationExecution;
import com.github.agentos.server.automation.AutomationModels.AutomationTask;
import com.github.agentos.server.automation.AutomationModels.ClaimRequest;
import com.github.agentos.server.automation.AutomationModels.ExecutionPage;
import com.github.agentos.server.automation.AutomationModels.FailExecutionRequest;
import com.github.agentos.server.automation.AutomationModels.HeartbeatRequest;
import com.github.agentos.server.automation.AutomationModels.SaveAutomationRequest;
import com.github.agentos.server.automation.AutomationModels.SchedulePreview;
import com.github.agentos.server.automation.AutomationModels.StartExecutionRequest;
import com.github.agentos.server.automation.AutomationModels.TriggerDefinition;
import com.github.agentos.server.automation.AutomationService;
import com.github.agentos.server.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 自动化任务管理、执行历史和桌面派发 REST API。 */
@RestController
@RequestMapping("/api")
public final class AutomationController {

    private final AutomationService service;

    public AutomationController(AutomationService service) {
        this.service = service;
    }

    @GetMapping("/automations")
    List<AutomationTask> list(HttpServletRequest request) {
        return service.list(identity(request));
    }

    @PostMapping("/automations")
    @ResponseStatus(HttpStatus.CREATED)
    AutomationTask create(@RequestBody SaveAutomationRequest body, HttpServletRequest request) {
        return service.create(body, identity(request));
    }

    @GetMapping("/automations/{id}")
    AutomationTask get(@PathVariable String id, HttpServletRequest request) {
        return service.get(id, identity(request));
    }

    @PutMapping("/automations/{id}")
    AutomationTask update(
            @PathVariable String id,
            @RequestBody SaveAutomationRequest body,
            HttpServletRequest request) {
        return service.update(id, body, identity(request));
    }

    @DeleteMapping("/automations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id, HttpServletRequest request) {
        service.delete(id, identity(request));
    }

    @PatchMapping("/automations/{id}/enabled")
    AutomationTask enabled(
            @PathVariable String id,
            @RequestBody EnabledRequest body,
            HttpServletRequest request) {
        if (body == null || body.enabled() == null) {
            throw new IllegalArgumentException("enabled must be provided");
        }
        return service.setEnabled(id, body.enabled(), identity(request));
    }

    @PostMapping("/automations/{id}/run")
    ResponseEntity<AutomationExecution> run(@PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.accepted().body(service.manualRun(id, identity(request)));
    }

    @PostMapping("/automations/schedule-preview")
    SchedulePreview preview(@RequestBody TriggerDefinition body) {
        return service.preview(body);
    }

    @GetMapping("/automation-executions")
    ExecutionPage executions(
            @RequestParam(defaultValue = "") String automationId,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        return service.executions(identity(request), automationId, status, offset, limit);
    }

    @GetMapping("/automation-executions/{id}")
    AutomationExecution execution(@PathVariable String id, HttpServletRequest request) {
        return service.execution(id, identity(request));
    }

    @PostMapping("/automation-desktop/heartbeat")
    AutomationClient heartbeat(@RequestBody HeartbeatRequest body, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("heartbeat body must be provided");
        return service.heartbeat(body.clientId(), body.platform(), body.appVersion(), identity(request));
    }

    @PostMapping("/automation-desktop/claim")
    ResponseEntity<AutomationExecution> claim(
            @RequestBody ClaimRequest body, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("claim body must be provided");
        return service.claim(body.clientId(), identity(request))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/automation-desktop/executions/{id}/start")
    AutomationExecution start(
            @PathVariable String id,
            @RequestBody StartExecutionRequest body,
            HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("start body must be provided");
        return service.start(id, body.clientId(), body.workspaceContext(), identity(request));
    }

    @PostMapping("/automation-desktop/executions/{id}/fail")
    AutomationExecution fail(
            @PathVariable String id,
            @RequestBody FailExecutionRequest body,
            HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("fail body must be provided");
        return service.fail(id, body.clientId(), body.reason(), identity(request));
    }

    private static RequestIdentity identity(HttpServletRequest request) {
        return RequestIdentity.from(request);
    }

    public record EnabledRequest(Boolean enabled) {
    }
}
