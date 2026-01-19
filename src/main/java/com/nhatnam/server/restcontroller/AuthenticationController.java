package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.request.AuthLoginRequest;
import com.nhatnam.server.dto.request.AuthRegisterRequest;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.dto.response.AuthResponse;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Log4j2
public class AuthenticationController {
    private final AuthService authService;

    /**
     * Đăng ký tài khoản mới
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Object>> register(@Valid @RequestBody AuthRegisterRequest request) {
        try {
            authService.register(request);
            return ResponseEntity.ok(
                    ApiResponse.success(StatusCode.SUCCESS, Collections.emptyMap(), "Register successfully")
            );
        } catch (Exception e) {
            log.error("Register failed: {}", e.getMessage());
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage())
            );
        }
    }

    /**
     * Đăng nhập
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@RequestBody AuthLoginRequest request) {
        try {
            AuthResponse response = authService.login(request);
            return ResponseEntity.ok(
                    ApiResponse.success(StatusCode.SUCCESS, response, "Login successfully")
            );
        } catch (Exception e) {
            log.error("Login failed from username {}, message: {}", request.getUsername(), e.getMessage());
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.UNAUTHORIZED, e.getMessage())
            );
        }
    }
}