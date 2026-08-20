package io.github.patchatlas.shared.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {RunController.class, ApiExceptionHandler.class})
class RunControllerWithoutPersistenceTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createWithoutPersistenceIs503() throws Exception {
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());
    }
}
