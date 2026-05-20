package com.nanfeng.billing.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.fail(ex.getMessage()));
    }

    @ExceptionHandler({
        BindException.class,
        ConstraintViolationException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidationException(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("请求参数不合法"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("Unhandled server exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail("服务暂时不可用"));
    }
}
