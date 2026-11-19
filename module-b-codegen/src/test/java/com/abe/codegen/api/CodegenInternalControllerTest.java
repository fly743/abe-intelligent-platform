package com.abe.codegen.api;

import com.abe.codegen.sandbox.DockerPreflight;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CodegenInternalControllerTest {
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new CodegenInternalController(".")).build();

    @Test void invalidPolicyIsReportedWithoutExecution() throws Exception {
        mvc.perform(post("/internal/codegen/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"A AND\",\"attributes\":[\"A\"],\"plaintext\":\"demo-secret-42\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generated.status", is("FAILURE")))
                .andExpect(jsonPath("$.execution.outcome", is("INVALID_INPUT")))
                .andExpect(jsonPath("$.execution.execution", is("NOT_RUN")))
                .andExpect(content().string(not(containsString("demo-secret-42"))));
    }

    @Test void unavailableEnvironmentKeepsGeneratedSource() throws Exception {
        assumeTrue(DockerPreflight.check().status() != DockerPreflight.Status.READY);
        mvc.perform(post("/internal/codegen/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"A AND B\",\"attributes\":[\"A\",\"B\"],\"plaintext\":\"demo-secret-42\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generated.status", is("SUCCESS")))
                .andExpect(jsonPath("$.execution.outcome", is("ENVIRONMENT_UNAVAILABLE")))
                .andExpect(jsonPath("$.execution.compilation", is("NOT_RUN")))
                .andExpect(content().string(not(containsString("demo-secret-42"))));
    }
    @Test void authorizedAndDeniedThroughHttp() throws Exception {
        assumeTrue(DockerPreflight.check().status() == DockerPreflight.Status.READY);
        for (String[] scenario : new String[][] {{"[\"A\",\"B\"]", "SUCCESS"}, {"[\"A\"]", "POLICY_NOT_SATISFIED"}}) {
            mvc.perform(post("/internal/codegen/verify").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"policy\":\"A AND B\",\"attributes\":" + scenario[0] + ",\"plaintext\":\"demo-secret-42\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.generated.status", is("SUCCESS")))
                    .andExpect(jsonPath("$.execution.compilation", is("SUCCESS")))
                    .andExpect(jsonPath("$.execution.outcome", is(scenario[1])))
                    .andExpect(jsonPath("$.execution.cleanup", is("SUCCESS")))
                    .andExpect(content().string(not(containsString("demo-secret-42"))));
        }
    }
}
