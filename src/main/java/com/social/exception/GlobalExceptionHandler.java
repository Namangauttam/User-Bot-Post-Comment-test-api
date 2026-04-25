package com.social.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HorizontalCapException.class)
    public ResponseEntity<Map<String, String>> handleHorizontalCap(HorizontalCapException ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)   // 429
                .body(Map.of(
                        "error", "HORIZONTAL_CAP_EXCEEDED",
                        "message", ex.getMessage()
                ));
    }

    @ExceptionHandler(VerticalCapException.class)
    public ResponseEntity<Map<String, String>> handleVerticalCap(VerticalCapException ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)   // 429
                .body(Map.of(
                        "error", "VERTICAL_CAP_EXCEEDED",
                        "message", ex.getMessage()
                ));
    }

    @ExceptionHandler(CooldownCapException.class)
    public ResponseEntity<Map<String, String>> handleCooldownCap(CooldownCapException ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)   // 429
                .body(Map.of(
                        "error", "COOLDOWN_ACTIVE",
                        "message", ex.getMessage()
                ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleGeneral(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)          // 400
                .body(Map.of(
                        "error", "BAD_REQUEST",
                        "message", ex.getMessage()
                ));
    }
}