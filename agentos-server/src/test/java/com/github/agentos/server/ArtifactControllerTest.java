package com.github.agentos.server;

import com.github.agentos.kernel.Artifact;
import com.github.agentos.kernel.LocalArtifactService;
import com.github.agentos.server.controller.ArtifactController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 产物查询、下载与删除接口测试。 */
class ArtifactControllerTest {

    @TempDir Path directory;

    @Test
    void listsDownloadsAndDeletesArtifacts() throws Exception {
        LocalArtifactService service = new LocalArtifactService(directory);
        byte[] bytes = "# 产物".getBytes(StandardCharsets.UTF_8);
        Artifact artifact = service.save(
                "session-1", "invocation-1", "报告.md", "text/markdown", bytes).orElseThrow();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArtifactController(service)).build();

        mvc.perform(get("/api/artifacts").param("sessionId", "session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].artifactId").value(artifact.artifactId()))
                .andExpect(jsonPath("$[0].sessionId").value("session-1"))
                .andExpect(jsonPath("$[0].invocationId").value("invocation-1"))
                .andExpect(jsonPath("$[0].filename").value("报告.md"))
                .andExpect(jsonPath("$[0].contentType").value("text/markdown"))
                .andExpect(jsonPath("$[0].sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());

        mvc.perform(get("/api/artifacts/{artifactId}", artifact.artifactId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().bytes(bytes));

        mvc.perform(delete("/api/artifacts/{artifactId}", artifact.artifactId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.artifactId").value(artifact.artifactId()));

        mvc.perform(get("/api/artifacts/{artifactId}", artifact.artifactId()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/artifacts/{artifactId}", artifact.artifactId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsEmptyListForSessionWithoutArtifacts() throws Exception {
        LocalArtifactService service = new LocalArtifactService(directory);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArtifactController(service)).build();

        mvc.perform(get("/api/artifacts").param("sessionId", "empty-session"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void downloadUnknownArtifactReturns404() throws Exception {
        LocalArtifactService service = new LocalArtifactService(directory);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArtifactController(service)).build();

        mvc.perform(get("/api/artifacts/{artifactId}", "missing"))
                .andExpect(status().isNotFound());
    }
}
