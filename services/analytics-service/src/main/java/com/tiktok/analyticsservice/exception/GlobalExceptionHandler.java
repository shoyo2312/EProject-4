package com.tiktok.analyticsservice.exception;

import com.tiktok.common.exception.BaseExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Everything is inherited from {@link BaseExceptionHandler}: the DomainException mapping, the
 * validation mapping, and — the reason this class no longer declares its own handlers — Spring
 * MVC's own request exceptions. A local {@code @ExceptionHandler(Exception.class)} outranks
 * {@code DefaultHandlerExceptionResolver}, so an unknown enum value in a query param or a
 * non-numeric path variable came back as 500 instead of 400.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends BaseExceptionHandler {
}
