package com.nhatnam.server.config;

import com.nhatnam.server.enumtype.StatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoHandlerFoundException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", StatusCode.NOT_FOUND);
        body.put("success", false);
        body.put("message", "Không tìm thấy tài nguyên");

        return ResponseEntity.ok(body);
    }
}