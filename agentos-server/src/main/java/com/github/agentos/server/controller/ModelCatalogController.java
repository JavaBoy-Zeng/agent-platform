package com.github.agentos.server.controller;

import com.github.agentos.server.model.ModelProviderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 只读模型目录：所有登录用户（含非管理员）可访问，
 * 用于聊天/自动化等任何需要选择运行模型的入口。
 *
 * <p>与 {@code /api/model-management} 的差异：本端点不返回 provider 的 endpoint、
 * API Key 摘要、连接测试状态等管理元数据，避免泄漏管理凭据。</p>
 */
@RestController
@RequestMapping("/api/models")
public class ModelCatalogController {

    private final ModelProviderService providers;

    public ModelCatalogController(ModelProviderService providers) {
        this.providers = providers;
    }

    @GetMapping
    public List<View> list() {
        return providers.snapshot().models().stream()
                .filter(ModelProviderService.ModelOptionView::enabled)
                .map(model -> new View(
                        model.id(),
                        model.modelId(),
                        model.providerName(),
                        model.providerType(),
                        model.modelType()))
                .toList();
    }

    /** 公开模型视图：仅包含非敏感元数据。 */
    public record View(
            String id, String modelId, String providerName,
            String providerType, String modelType) {
    }
}