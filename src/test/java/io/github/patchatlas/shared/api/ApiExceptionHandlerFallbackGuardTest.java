package io.github.patchatlas.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerFallbackGuardTest {

    private static final String SECRET = "UNIQUE_SECRET_TOKEN_MUST_NOT_APPEAR";

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void unlistedExceptionStillReturns500WithoutLeakingMessage() throws Exception {
        String body = mockMvc.perform(get("/boom"))
                .andExpect(status().is(500))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("an unexpected error occurred"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(SECRET);
        assertThat(body).doesNotContain("Exception");
        assertThat(body).doesNotContain("Caused by");
    }

    @Test
    void exceptionClassFallbackHandlerIsStillDeclared() {
        boolean declared = false;
        for (var method : ApiExceptionHandler.class.getDeclaredMethods()) {
            var mapping = method.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);
            if (mapping == null) {
                continue;
            }
            for (Class<?> type : mapping.value()) {
                if (type == Exception.class) {
                    declared = true;
                }
            }
        }
        assertThat(declared).isTrue();
    }

    @RestController
    static class BoomController {
        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException(SECRET);
        }
    }
}
