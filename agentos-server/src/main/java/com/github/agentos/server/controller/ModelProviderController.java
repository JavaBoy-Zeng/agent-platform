package com.github.agentos.server.controller;

import com.github.agentos.server.model.ModelProviderService;
import com.github.agentos.server.model.ModelProviderService.ConnectionTestResult;
import com.github.agentos.server.model.ModelProviderService.ManagementSnapshot;
import com.github.agentos.server.model.ModelProviderService.ProviderView;
import com.github.agentos.server.model.ModelProviderService.SaveProviderRequest;
import com.github.agentos.server.security.RequestIdentity;
import com.github.agentos.server.security.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 管理页面使用的模型配置 API。 */
@RestController
@RequestMapping("/api/model-management")
public class ModelProviderController {

    private final ModelProviderService service;
    private final boolean authEnabled;

    public ModelProviderController(ModelProviderService service, boolean authEnabled) {
        this.service = service;
        this.authEnabled = authEnabled;
    }

    @GetMapping
    ManagementSnapshot snapshot(HttpServletRequest request) {
        requireAdmin(request);
        return service.snapshot();
    }

    @GetMapping("/validate")
    List<ModelProviderService.ModelValidation> validate(HttpServletRequest request) {
        requireAdmin(request);
        return service.validateModels();
    }

    @PostMapping("/providers")
    ProviderView create(@RequestBody SaveProviderRequest body, HttpServletRequest request) {
        requireAdmin(request);
        return service.create(body);
    }

    @PutMapping("/providers/{providerId}")
    ProviderView update(
            @PathVariable String providerId,
            @RequestBody SaveProviderRequest body,
            HttpServletRequest request) {
        requireAdmin(request);
        return service.update(providerId, body);
    }

    @DeleteMapping("/providers/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String providerId, HttpServletRequest request) {
        requireAdmin(request);
        service.delete(providerId);
    }

    @PostMapping("/providers/{providerId}/test")
    ConnectionTestResult test(@PathVariable String providerId, HttpServletRequest request) {
        requireAdmin(request);
        return service.test(providerId);
    }

    private void requireAdmin(HttpServletRequest request) {
        if (authEnabled && !RequestIdentity.from(request).roles().contains(UserAccount.ROLE_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要 ADMIN 角色");
        }
    }
}
