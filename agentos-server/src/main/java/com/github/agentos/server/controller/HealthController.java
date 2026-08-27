package com.github.agentos.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 存活探针：供桌面壳与部署脚本做连通性检测。
 *
 * <p>位于 {@code /api/*} 过滤链内，启用 API Key 时需携带有效密钥，
 * 与业务接口的访问控制保持一致。</p>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
