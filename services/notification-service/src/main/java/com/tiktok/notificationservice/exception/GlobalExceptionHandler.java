package com.tiktok.notificationservice.exception;

import com.tiktok.common.exception.BaseExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Everything is inherited from {@link BaseExceptionHandler}. The local copy this replaced mapped
 * Spring MVC's own request exceptions — a malformed query param, an unknown enum value — to 500,
 * because its {@code Exception.class} catch-all outranks {@code DefaultHandlerExceptionResolver}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends BaseExceptionHandler {
}
