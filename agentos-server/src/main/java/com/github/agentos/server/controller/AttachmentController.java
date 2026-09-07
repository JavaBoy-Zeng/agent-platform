package com.github.agentos.server.controller;

import com.github.agentos.tool.builtin.file.access.RootedFileAccessPolicy;
import com.github.agentos.server.security.SessionAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 会话附件上传接口（网页端）。
 *
 * <p>附件存储在文件访问根目录下的 {@code attachments/<sessionId>/}，返回根目录相对路径。
 * Agent 通过 {@code file_read} 工具按该路径真实读取，读取状态来自运行时工具事件而非模型生成，
 * 与桌面端"复制到工作区后由 Agent 读取"的模式保持一致。</p>
 */
@RestController
@RequestMapping("/api/attachments")
public final class AttachmentController {

    private static final int MAX_FILES_PER_REQUEST = 10;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 128;
    private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final RootedFileAccessPolicy fileAccessPolicy;
    private final SessionAuthorization authorization;

    /** 创建附件上传控制器。 */
    public AttachmentController(
            RootedFileAccessPolicy fileAccessPolicy, SessionAuthorization authorization) {
        this.fileAccessPolicy = fileAccessPolicy;
        this.authorization = authorization;
    }

    /** 上传附件到会话目录，返回可被 file_read 工具读取的相对路径列表。 */
    @PostMapping
    public List<AttachmentView> upload(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("files") List<MultipartFile> files,
            HttpServletRequest request) {
        String session = requireSafeSessionId(sessionId);
        authorization.claim(session, request);
        if (files == null || files.isEmpty()) {
            throw badRequest("files must not be empty");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw badRequest("too many files (max " + MAX_FILES_PER_REQUEST + ")");
        }
        List<AttachmentView> uploaded = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw badRequest("attachment must not be empty");
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw badRequest("attachment too large (max 20MB): "
                        + file.getOriginalFilename());
            }
            uploaded.add(store(session, file));
        }
        return uploaded;
    }

    private AttachmentView store(String session, MultipartFile file) {
        String name = sanitizeFileName(file.getOriginalFilename());
        String relativePath = uniqueTarget("attachments/" + session, name);
        // 复用根目录隔离策略：目标必须落在允许根目录内
        Path target = fileAccessPolicy.authorizeWrite(Path.of(relativePath));
        try (InputStream input = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(input, target);
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "无法保存附件: " + name, exception);
        }
        return new AttachmentView(name, relativePath, file.getSize());
    }

    /** 同名附件不覆盖，追加 -1/-2 序号。 */
    private String uniqueTarget(String directory, String fileName) {
        Path root = fileAccessPolicy.root();
        String candidate = directory + "/" + fileName;
        if (!Files.exists(root.resolve(candidate))) {
            return candidate;
        }
        String stem = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            stem = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int index = 1; index < 1000; index++) {
            candidate = directory + "/" + stem + "-" + index + extension;
            if (!Files.exists(root.resolve(candidate))) {
                return candidate;
            }
        }
        throw badRequest("无法为附件分配唯一文件名: " + fileName);
    }

    /** 剥离目录成分与控制字符；拒绝 {@code .}/{@code ..} 与空名称。 */
    private static String sanitizeFileName(String raw) {
        String value = raw == null ? "" : raw.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("\\p{Cntrl}", "").trim();
        if (value.isBlank() || ".".equals(value) || "..".equals(value)) {
            throw badRequest("附件名称无效");
        }
        return value.length() > MAX_NAME_LENGTH
                ? value.substring(0, MAX_NAME_LENGTH)
                : value;
    }

    private static String requireSafeSessionId(String sessionId) {
        String value = sessionId == null ? "" : sessionId.trim();
        if (!SAFE_SESSION_ID.matcher(value).matches()) {
            throw badRequest("sessionId 只能包含字母、数字、- 和 _，长度不超过 64");
        }
        return value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** 上传结果视图，字段与桌面端 UploadedAttachment 保持一致。 */
    public record AttachmentView(String name, String relativePath, long size) {
    }
}
