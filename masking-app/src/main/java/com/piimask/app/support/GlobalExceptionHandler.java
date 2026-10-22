package com.piimask.app.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    /** 잘못된 JSON, 없는 경로, 메서드 불일치 등 Spring 이 이미 4xx 로 분류한 예외 */
    @ExceptionHandler(ErrorResponse.class)
    public ResponseEntity<Map<String, String>> spring(ErrorResponse e) {
        String detail = e.getBody().getDetail();
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", detail == null ? "잘못된 요청입니다" : detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "요청 값이 올바르지 않습니다"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        // 예외 메시지에 원천 데이터가 섞일 수 있으므로 로그에는 유형만 남긴다
        log.error("unhandled error: {}", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "처리 중 오류가 발생했습니다: " + Errors.safeMessage(e)));
    }
}
