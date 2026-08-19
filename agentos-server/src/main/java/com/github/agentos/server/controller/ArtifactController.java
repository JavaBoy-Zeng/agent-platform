package com.github.agentos.server.controller;

import com.github.agentos.kernel.Artifact;
import com.github.agentos.kernel.ArtifactContent;
import com.github.agentos.kernel.ArtifactService;
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

    /** 创建产物接口。 */
    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /** 列出指定会话的全部产物，按登记时间倒序。 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam String sessionId) {
        return artifactService.list(sessionId).stream()
                .map(ArtifactController::view)
                .toList();
    }

    /** 按标识下载产物原始内容。 */
    @GetMapping("/{artifactId}")
    public ResponseEntity<byte[]> download(@PathVariable String artifactId) {
        return artifactService.load(artifactId)
                .map(ArtifactController::downloadResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除指定产物。 */
    @DeleteMapping("/{artifactId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String artifactId) {
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
