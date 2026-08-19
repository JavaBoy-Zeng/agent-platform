package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 本地文件系统产物存储的登记、恢复、列举与删除测试。 */
class LocalArtifactServiceTest {

    @TempDir Path directory;

    @Test
    void savesAndReloadsArtifactContent() {
        LocalArtifactService service = new LocalArtifactService(directory);
        byte[] bytes = "# 标题".getBytes(StandardCharsets.UTF_8);

        Artifact saved = service.save(
                "session-1", "invocation-1", "报告.md", "text/markdown", bytes).orElseThrow();

        assertThat(saved.artifactId()).isNotBlank();
        assertThat(saved.sessionId()).isEqualTo("session-1");
        assertThat(saved.invocationId()).isEqualTo("invocation-1");
        assertThat(saved.filename()).isEqualTo("报告.md");
        assertThat(saved.contentType()).isEqualTo("text/markdown");
        assertThat(saved.sizeBytes()).isEqualTo(bytes.length);

        ArtifactContent content = service.load(saved.artifactId()).orElseThrow();
        assertThat(content.bytes()).isEqualTo(bytes);
        assertThat(content.artifact()).isEqualTo(saved);
    }

    @Test
    void recoversArtifactsAcrossStoreInstances() {
        LocalArtifactService first = new LocalArtifactService(directory);
        Artifact artifact = first.save(
                "session-1", "", "data.bin", "application/octet-stream", new byte[]{1, 2, 3})
                .orElseThrow();

        LocalArtifactService reloaded = new LocalArtifactService(directory);

        assertThat(reloaded.load(artifact.artifactId())).isPresent();
        assertThat(reloaded.list("session-1"))
                .extracting(Artifact::artifactId)
                .containsExactly(artifact.artifactId());
    }

    @Test
    void listsOnlyRequestedSessionOrderedByNewestFirst() throws Exception {
        LocalArtifactService service = new LocalArtifactService(directory);
        Artifact older = service.save(
                "session-1", "", "a.txt", "text/plain", new byte[]{1}).orElseThrow();
        Thread.sleep(5);
        Artifact newer = service.save(
                "session-1", "", "b.txt", "text/plain", new byte[]{2}).orElseThrow();
        service.save("session-2", "", "c.txt", "text/plain", new byte[]{3});

        assertThat(service.list("session-1"))
                .extracting(Artifact::artifactId)
                .containsExactly(newer.artifactId(), older.artifactId());
        assertThat(service.list("unknown-session")).isEmpty();
    }

    @Test
    void deletesArtifactAndReportsMissingAsFalse() {
        LocalArtifactService service = new LocalArtifactService(directory);
        Artifact artifact = service.save(
                "session-1", "", "gone.txt", "text/plain", new byte[]{1}).orElseThrow();

        assertThat(service.delete(artifact.artifactId())).isTrue();
        assertThat(service.load(artifact.artifactId())).isEmpty();
        assertThat(service.delete(artifact.artifactId())).isFalse();
    }

    @Test
    void sanitizesFilenameAndRejectsTraversalIds() {
        LocalArtifactService service = new LocalArtifactService(directory);

        Artifact saved = service.save(
                "session-1", "", "../../evil.txt", "text/plain", new byte[]{1}).orElseThrow();
        assertThat(saved.filename()).isEqualTo("evil.txt");

        assertThat(service.load("../../" + saved.artifactId())).isEmpty();
        assertThat(service.delete("../" + saved.artifactId())).isFalse();
    }

    @Test
    void rejectsInvalidFilenames() {
        LocalArtifactService service = new LocalArtifactService(directory);

        assertThatThrownBy(() -> service.save("session-1", "", "..", "text/plain", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save("session-1", "", "   ", "text/plain", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noopServiceReturnsEmptyResults() {
        assertThat(ArtifactService.NOOP.save("s", "", "a.txt", "text/plain", new byte[0]))
                .isEmpty();
        assertThat(ArtifactService.NOOP.load("any")).isEmpty();
        assertThat(ArtifactService.NOOP.list("s")).isEqualTo(List.of());
        assertThat(ArtifactService.NOOP.delete("any")).isFalse();
    }
}
