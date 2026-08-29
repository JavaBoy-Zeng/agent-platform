package com.github.agentos.server;

import com.github.agentos.server.controller.AttachmentController;
import com.github.agentos.tool.builtin.file.access.RootedFileAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 会话附件上传接口测试。 */
class AttachmentControllerTest {

    @TempDir
    Path root;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new AttachmentController(new RootedFileAccessPolicy(root))).build();
    }

    @Test
    void uploadStoresFileUnderSessionDirectory() throws Exception {
        mvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile(
                                "files", "brief.md", "text/markdown",
                                "# 任务简介".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("sessionId", "sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("brief.md"))
                .andExpect(jsonPath("$[0].relativePath").value("attachments/sess-1/brief.md"))
                .andExpect(jsonPath("$[0].size").value(14));

        assertThat(new String(Files.readAllBytes(
                root.resolve("attachments/sess-1/brief.md")),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("# 任务简介");
    }

    @Test
    void duplicateNamesGetUniqueSuffixInsteadOfOverwrite() throws Exception {
        byte[] first = "version-1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] second = "version-2".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        mvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("files", "data.txt", "text/plain", first))
                        .param("sessionId", "sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relativePath").value("attachments/sess-1/data.txt"));

        mvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("files", "data.txt", "text/plain", second))
                        .param("sessionId", "sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relativePath").value("attachments/sess-1/data-1.txt"));

        assertThat(Files.readAllBytes(root.resolve("attachments/sess-1/data.txt"))).isEqualTo(first);
        assertThat(Files.readAllBytes(root.resolve("attachments/sess-1/data-1.txt"))).isEqualTo(second);
    }

    @Test
    void sanitizesDirectoryTraversalInFileName() throws Exception {
        // 恶意文件名携带目录成分：必须剥离，只保留文件名且落在会话目录内
        mvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile(
                                "files", "../../evil.txt", "text/plain",
                                "bad".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("sessionId", "sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("evil.txt"))
                .andExpect(jsonPath("$[0].relativePath").value("attachments/sess-1/evil.txt"));

        assertThat(Files.exists(root.resolve("evil.txt"))).isFalse();
        assertThat(Files.exists(root.resolve("attachments/sess-1/evil.txt"))).isTrue();
    }

    @Test
    void rejectsUnsafeSessionId() throws Exception {
        mvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile(
                                "files", "a.txt", "text/plain",
                                "x".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("sessionId", "../escape"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingFiles() throws Exception {
        mvc.perform(multipart("/api/attachments").param("sessionId", "sess-1"))
                .andExpect(status().isBadRequest());
    }
}
