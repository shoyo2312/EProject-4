package com.tiktok.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every entity in the codebase carries {@code @Version} through BaseEntity, so any two requests
 * editing the same row can lose that race — and with no handler for it the failure fell through to
 * {@code @ExceptionHandler(Exception.class)} and came back as 500. That told the client its request
 * was broken and the on-call engineer that the server was, when the truth is neither: the request
 * was fine and the right answer is to re-read and retry.
 *
 * <p>Both the JPA and the plain DAO exception are exercised, because the two stores raise different
 * subclasses — JPA services throw ObjectOptimisticLockingFailureException, Mongo does not — and the
 * handler is declared on their shared parent precisely so neither has to be named.
 */
class BaseExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ContendedController())
            .setControllerAdvice(new TestExceptionHandler())
            .build();

    @Test
    void jpaOptimisticLockFailure_answers409_notServerError() throws Exception {
        mockMvc.perform(get("/contended/jpa"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void storeAgnosticOptimisticLockFailure_answers409_notServerError() throws Exception {
        mockMvc.perform(get("/contended/dao"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    /** Anything that is genuinely a fault must keep answering 500, or the handler is too greedy. */
    @Test
    void unrelatedFailure_stillAnswers500() throws Exception {
        mockMvc.perform(get("/contended/broken"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    /** A rate limit tells the client when to come back in a header, not only in prose. */
    @Test
    void domainException_carriesItsHeaders() throws Exception {
        mockMvc.perform(get("/contended/throttled"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "7"))
                .andExpect(jsonPath("$.code").value("SLOW_DOWN"));
    }

    @Test
    void domainException_withoutHeaders_answersAsBefore() throws Exception {
        mockMvc.perform(get("/contended/missing"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("THING_NOT_FOUND"));
    }

    @RestControllerAdvice
    static class TestExceptionHandler extends BaseExceptionHandler {
    }

    static class ThrottledException extends DomainException {

        ThrottledException() {
            super("SLOW_DOWN", "Too many requests", HttpStatus.TOO_MANY_REQUESTS);
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "7");
            return headers;
        }
    }

    @RestController
    static class ContendedController {

        @GetMapping("/contended/jpa")
        void jpa() {
            throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
        }

        @GetMapping("/contended/dao")
        void dao() {
            throw new OptimisticLockingFailureException("version mismatch");
        }

        @GetMapping("/contended/broken")
        void broken() {
            throw new IllegalStateException("something genuinely unexpected");
        }

        @GetMapping("/contended/throttled")
        void throttled() {
            throw new ThrottledException();
        }

        @GetMapping("/contended/missing")
        void missing() {
            throw new ResourceNotFoundException("THING_NOT_FOUND", "No such thing");
        }
    }
}
