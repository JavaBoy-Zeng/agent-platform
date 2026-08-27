package com.github.agentos.server.controller;

import com.github.agentos.server.catalog.RuntimeCatalogService;
import com.github.agentos.server.catalog.RuntimeCatalogService.AgentDetail;
import com.github.agentos.server.catalog.RuntimeCatalogService.CatalogSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 为 Console 管理面板提供不包含密钥的运行时只读目录。 */
@RestController
@RequestMapping("/api/console")
public final class ConsoleCatalogController {
    private final RuntimeCatalogService catalogService;

    public ConsoleCatalogController(RuntimeCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** 返回 Agent、工具、MCP、技能、模型与运行预算的当前配置快照。 */
    @GetMapping("/catalog")
    public CatalogSnapshot catalog() {
        return catalogService.snapshot();
    }

    /** 返回单个 Agent 的详情；未注册时返回 404。 */
    @GetMapping("/agents/{agentId}")
    public ResponseEntity<AgentDetail> agent(@PathVariable String agentId) {
        return catalogService.agent(agentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
