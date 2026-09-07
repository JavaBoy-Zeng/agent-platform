package com.github.agentos.server.controller;

import com.github.agentos.kernel.Artifact;
import com.github.agentos.kernel.ArtifactContent;
import com.github.agentos.kernel.ArtifactService;
import com.github.agentos.server.security.SessionAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行产物查询与下载接口。
 *
 * <p>工具写入的报告、文档等文件会自动登记为会话产物；
 * 前端凭 {@code artifactId} 下载原始内容，替代直接暴露本地文件路径。</p>
 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactService artifactService;
    private final SessionAuthorization authorization;

    /** 创建产物接口。 */
    public ArtifactController(
            ArtifactService artifactService, SessionAuthorization authorization) {
        this.artifactService = artifactService;
        this.authorization = authorization;
    }

    /** 列出指定会话的全部产物，按登记时间倒序。 */
    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam String sessionId, HttpServletRequest request) {
        authorization.requireOwned(sessionId, request);
        return artifactService.list(sessionId).stream()
                .map(ArtifactController::view)
                .toList();
    }

    /** 按标识下载产物原始内容。 */
    @GetMapping("/{artifactId}")
    public ResponseEntity<byte[]> download(
            @PathVariable String artifactId, HttpServletRequest request) {
        Artifact artifact = artifactService.metadata(artifactId).orElse(null);
        if (artifact == null) return ResponseEntity.notFound().build();
        authorization.requireOwned(artifact.sessionId(), request);
        ArtifactContent content = artifactService.load(artifactId).orElse(null);
        if (content == null) return ResponseEntity.notFound().build();
        return downloadResponse(content);
    }

    /** 删除指定产物。 */
    @DeleteMapping("/{artifactId}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String artifactId, HttpServletRequest request) {
        Artifact artifact = artifactService.metadata(artifactId).orElse(null);
        if (artifact == null) return ResponseEntity.notFound().build();
        authorization.requireOwned(artifact.sessionId(), request);
        if (!artifactService.delete(artifactId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "deleted", true,
                "artifactId", artifactId));
    }

    private static ResponseEntity<byte[]> downloadResponse(ArtifactContent content) {
        Artifact artifact = content.artifact();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(artifact.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(artifact.filename(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(artifact.sizeBytes());
        return ResponseEntity.ok().headers(headers).body(content.bytes());
    }

    private static Map<String, Object> view(Artifact artifact) {
        return Map.of(
                "artifactId", artifact.artifactId(),
                "sessionId", artifact.sessionId(),
                "invocationId", artifact.invocationId(),
                "filename", artifact.filename(),
                "contentType", artifact.contentType(),
                "sizeBytes", artifact.sizeBytes(),
                "createdAt", artifact.createdAt().toString());
    }
}
