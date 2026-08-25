package com.tiktok.userservice.exception;

import com.tiktok.common.exception.BaseExceptionHandler;
import com.tiktok.common.response.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * All the mapping lives in {@link BaseExceptionHandler}; this exists so the advice is a bean in
 * a package this service's component scan reaches. Add handlers here only for exceptions
 * specific to user-service.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends BaseExceptionHandler {

    /**
     * A file past {@code spring.servlet.multipart.max-file-size}. The container throws it before
     * the controller runs, so {@link com.tiktok.userservice.service.AvatarUploadService}'s own
     * size check never sees it, and the caller would otherwise be told the size of the limit by a
     * bare "Payload Too Large" — this names the field instead.
     *
     * <p>An <b>override</b>, not a second {@code @ExceptionHandler}: since Spring 6.1
     * {@link org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler}
     * already declares this type, and adding another mapping for it fails the context at startup
     * with "Ambiguous @ExceptionHandler method mapped for MaxUploadSizeExceededException".
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return handleExceptionInternal(
                ex,
                ApiResponse.error("INVALID_AVATAR", "The image is too large"),
                headers,
                HttpStatus.PAYLOAD_TOO_LARGE,
                request);
    }
}
