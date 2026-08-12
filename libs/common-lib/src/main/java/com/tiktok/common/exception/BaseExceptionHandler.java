package com.tiktok.common.exception;

import com.tiktok.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every service's {@code @RestControllerAdvice} extends this, so the mapping from exception to
 * {@link ApiResponse} is defined once instead of being copy-pasted per service.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} is the point of the class. Without it, an
 * advice that only declares {@code @ExceptionHandler(Exception.class)} silently swallows Spring
 * MVC's own request exceptions: {@code ExceptionHandlerExceptionResolver} runs before
 * {@code DefaultHandlerExceptionResolver}, so the catch-all wins and a malformed body, an
 * unknown enum value or a non-numeric path variable — all plain client mistakes — came back as
 * 500 INTERNAL_ERROR with a stack trace in the logs. The base class contributes a handler for
 * each of those exception types; the resolver picks the most specific match, so they now map to
 * their real 4xx and {@code Exception.class} is left with genuinely unexpected failures only.
 *
 * <p>Servlet-only, like the {@code spring-boot-starter-web} it is compiled against — api-gateway
 * is WebFlux and must not extend this.
 */
@Slf4j
public abstract class BaseExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomainException(DomainException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    /**
     * A lost race on {@code @Version}, which every {@code BaseEntity} carries: two requests read
     * the same row and the second one to write finds the version already moved. That is a
     * conflict, not a server fault — the caller's request was well formed and retrying it against
     * the current state will usually succeed — so it answers 409 rather than falling through to
     * the catch-all below as a 500. The distinction is the whole point: 500 tells a client to
     * give up and an on-call engineer to start looking, while 409 tells the client to re-read and
     * try again, which is exactly the right move.
     *
     * <p>Catches Spring's DAO-level {@code OptimisticLockingFailureException} rather than the JPA
     * subclass, so the same mapping covers video-service's Mongo {@code @Version} documents.
     *
     * <p>Logged without a stack trace: under contention this is expected, and a trace per
     * concurrent edit buries the failures that are real.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailure(
            OptimisticLockingFailureException ex) {
        log.warn("Rejected concurrent modification: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("CONCURRENT_MODIFICATION",
                        "The resource was modified by another request. Re-read it and try again."));
    }

    /**
     * Last resort. Anything reaching here is a bug rather than a bad request, so it keeps the
     * stack trace and the opaque message — the details stay in the log, not in the response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        log.error("Unexpected error handling request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");

        return handleExceptionInternal(ex, ApiResponse.error("VALIDATION_ERROR", message), headers, status, request);
    }

    /**
     * Single funnel for every exception the base class handles, so each one answers in the same
     * envelope as the rest of the API instead of Spring's default ProblemDetail.
     *
     * <p>Logged at warn without a stack trace: these are client errors, and a trace per mistyped
     * URL is noise that buries the real 500s. The message shown to the caller is the sanitised
     * {@link ErrorResponse} detail ("Invalid request content."), never {@code ex.getMessage()},
     * which for a Jackson failure spells out internal class names and package structure.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ApiResponse<?>) {
            return super.handleExceptionInternal(ex, body, headers, status, request);
        }

        log.warn("Rejected request with {}: {}", status, ex.getMessage());
        return super.handleExceptionInternal(
                ex, ApiResponse.error(codeOf(status), detailOf(ex, status)), headers, status, request);
    }

    private static String codeOf(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "REQUEST_ERROR" : resolved.name();
    }

    private static String detailOf(Exception ex, HttpStatusCode status) {
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getBody().getDetail() != null) {
            return errorResponse.getBody().getDetail();
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "Request could not be processed" : resolved.getReasonPhrase();
    }
}
