package com.github.agentos.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * 本地文件系统产物存储。
 *
 * <p>每个产物一个目录：{@code <root>/<artifactId>/artifact.properties} 保存元数据，
 * {@code content.bin} 保存原始字节。产物标识由存储侧生成（UUID），
 * 目录名不依赖用户输入，天然规避路径注入。内容写入采用临时文件替换，
 * 与 {@link FileCheckpointStore} 保持一致的崩溃安全性。</p>
 */
public final class LocalArtifactService implements ArtifactService {

    private static final String METADATA_FILE = "artifact.properties";
    private static final String CONTENT_FILE = "content.bin";

    private final Path root;

    /** 创建存储并确保根目录存在。 */
    public LocalArtifactService(Path root) {
        this.root = Objects.requireNonNull(root, "root must not be null")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create artifact directory", exception);
        }
    }

    /** 登记产物：写入内容与元数据后返回产物元数据。 */
    @Override
    public Optional<Artifact> save(
            String sessionId, String invocationId,
            String filename, String contentType, byte[] bytes) {
        Artifact artifact = new Artifact(
                UUID.randomUUID().toString(),
                sessionId,
                invocationId,
                sanitizeFilename(filename),
                contentType,
                bytes == null ? 0 : bytes.length,
                Instant.now());
        Path directory = root.resolve(artifact.artifactId());
        try {
            Files.createDirectories(directory);
            Path content = directory.resolve(CONTENT_FILE);
            Path temporary = Files.createTempFile(directory, "content", ".tmp");
            Files.write(temporary, bytes == null ? new byte[0] : bytes);
            move(temporary, content);
            Path metadata = directory.resolve(METADATA_FILE);
            Path metadataTemporary = Files.createTempFile(directory, "meta", ".tmp");
            try (OutputStream output = Files.newOutputStream(metadataTemporary)) {
                toProperties(artifact).store(output, "AgentOS artifact");
            }
            move(metadataTemporary, metadata);
            return Optional.of(artifact);
        } catch (IOException exception) {
            deleteQuietly(directory);
            throw new UncheckedIOException("failed to save artifact", exception);
        }
    }

    /** 按标识加载产物内容与元数据。 */
    @Override
    public Optional<ArtifactContent> load(String artifactId) {
        Path directory = artifactDirectory(artifactId);
        if (directory == null || !Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(directory.resolve(METADATA_FILE))) {
                properties.load(input);
            }
            Artifact artifact = fromProperties(artifactId, properties);
            byte[] bytes = Files.readAllBytes(directory.resolve(CONTENT_FILE));
            return Optional.of(new ArtifactContent(artifact, bytes));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "failed to load artifact: " + artifactId, exception);
        }
    }

    /** 列出指定会话的全部产物，按登记时间倒序。 */
    @Override
    public List<Artifact> list(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<Artifact> artifacts = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path directory : stream) {
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                readMetadata(directory).stream()
                        .filter(artifact -> artifact.sessionId().equals(sessionId.trim()))
                        .forEach(artifacts::add);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to list artifacts", exception);
        }
        artifacts.sort(Comparator.comparing(Artifact::createdAt).reversed());
        return List.copyOf(artifacts);
    }

    /** 递归删除产物目录。 */
    @Override
    public boolean delete(String artifactId) {
        Path directory = artifactDirectory(artifactId);
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
            return true;
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to delete artifact: " + artifactId, exception);
        }
    }

    /** 产物标识只允许字母数字与连字符，防止目录穿越。 */
    private Path artifactDirectory(String artifactId) {
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9-]+")) {
            return null;
        }
        return root.resolve(artifactId);
    }

    private Optional<Artifact> readMetadata(Path directory) {
        Path metadata = directory.resolve(METADATA_FILE);
        if (!Files.exists(metadata)) {
            return Optional.empty();
        }
        try {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(metadata)) {
                properties.load(input);
            }
            return Optional.of(fromProperties(directory.getFileName().toString(), properties));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "failed to read artifact metadata: " + directory, exception);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path directory) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // 清理失败时保留残余目录，不影响异常向上传播。
        }
    }

    /** 文件名只保留最后一段路径，且不得为空、点号或超长。 */
    private static String sanitizeFilename(String filename) {
        String name = filename == null ? "" : filename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank() || ".".equals(name) || "..".equals(name) || name.length() > 255) {
            throw new IllegalArgumentException("invalid artifact filename: " + filename);
        }
        return name;
    }

    private static Properties toProperties(Artifact artifact) {
        Properties properties = new Properties();
        properties.setProperty("sessionId", artifact.sessionId());
        properties.setProperty("invocationId", artifact.invocationId());
        properties.setProperty("filename", artifact.filename());
        properties.setProperty("contentType", artifact.contentType());
        properties.setProperty("sizeBytes", String.valueOf(artifact.sizeBytes()));
        properties.setProperty("createdAt", artifact.createdAt().toString());
        return properties;
    }

    private static Artifact fromProperties(String artifactId, Properties properties) {
        return new Artifact(
                artifactId,
                required(properties, "sessionId"),
                properties.getProperty("invocationId", ""),
                required(properties, "filename"),
                required(properties, "contentType"),
                Long.parseLong(required(properties, "sizeBytes")),
                Instant.parse(required(properties, "createdAt")));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("missing artifact property: " + key);
        }
        return value;
    }
}
