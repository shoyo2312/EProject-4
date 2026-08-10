package com.tiktok.authservice.exception;

import com.tiktok.common.exception.BaseExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * All the mapping lives in {@link BaseExceptionHandler}; this exists so the advice is a bean in
 * a package this service's component scan reaches. Add {@code @ExceptionHandler} methods here
 * only for exceptions specific to auth-service.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends BaseExceptionHandler {
}
