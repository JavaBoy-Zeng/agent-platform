package com.github.agentos.server;

import com.github.agentos.planner.ModelClient;
import com.github.agentos.server.model.RoutingModelClients;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "agentos.memory.mode=memory",
                "agentos.model.model=test-model",
                "agentos.model.endpoint=http://127.0.0.1:1/v1/chat/completions"
        })
class ModelClientConfigurationIntegrationTest {

    @Autowired
    private ModelClient modelClient;

    @Test
    void registersDefaultModelClientOutsideDemoProfile() {
        assertThat(modelClient).isInstanceOf(RoutingModelClients.Planner.class);
    }
}
