package com.nhatnam.server.tools.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.dto.ToolsAuthDtos.*;
import com.nhatnam.server.tools.service.ToolsAuthTokenService;
import com.nhatnam.server.tools.service.ToolsUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Đăng nhập, verify token, đổi mật khẩu bản thân.
 * Base path: /api/tools/auth — nằm ở PUBLIC_PREFIXES của {@link com.nhatnam.server.tools.config.ToolsAuthFilter}
 * nên không tự chặn chính nó.
 */
@RestController
@RequestMapping("/api/tools/auth")
@RequiredArgsConstructor
@Log4j2
public class ToolsAuthController {

    /** 12 giờ khi không tick "Ghi nhớ" — vừa đủ 1 ca làm việc mà không phải đăng nhập lại giữa chừng */
    private static final long TTL_SESSION_MS = 12L * 60 * 60 * 1000;

    private final ToolsUserService userService;
    private final ToolsAuthTokenService tokenService;

    /**
     * POST /api/tools/auth/login
     *
     * Body: { username, password, remember? }
     * Trả 200 + token nếu OK, 200 + code 401 nếu sai (ApiResponse envelope).
     * KHÔNG trả 401 HTTP ở đây để FE không bị axios interceptor tự đá về login
     * khi... đang ở login.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest req) {
        try {
            var user = userService.authenticate(req.getUsername(), req.getPassword());

            Long ttl = Boolean.TRUE.equals(req.getRemember()) ? null : TTL_SESSION_MS;
            String token = tokenService.issue(user.getUsername(), Boolean.TRUE.equals(user.getIsAdmin()), ttl);
            var parsed = tokenService.parse(token);

            return ResponseEntity.ok(ApiResponse.success(
                    LoginResponse.builder()
                            .token(token)
                            .username(user.getUsername())
                            .displayName(user.getDisplayName())
                            .admin(user.getIsAdmin())
                            .expiresAt(parsed != null ? parsed.expMs() : null)
                            .build(),
                    "Đăng nhập thành công"));

        } catch (ToolsException e) {
            log.info("[Tools][Auth] login failed: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(401, e.getMessage()));
        } catch (Exception e) {
            log.error("[Tools][Auth] login error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Đăng nhập thất bại."));
        }
    }

    /**
     * POST /api/tools/auth/verify
     *
     * Client gọi khi khởi động app để biết token còn hạn hay không. Nếu còn,
     * trả lại thông tin user (thay vì lưu display name trong localStorage —
     * cho phép admin đổi tên hiển thị mà không cần user đăng nhập lại).
     */
    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<LoginResponse>> verify(@RequestHeader(value = "Authorization", required = false) String header) {
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7).trim() : null;
        var parsed = tokenService.parse(token);
        if (parsed == null) {
            return ResponseEntity.ok(ApiResponse.error(401, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn."));
        }
        try {
            var user = userService.mustFindByUsername(parsed.username());
            if (!Boolean.TRUE.equals(user.getActive())) {
                return ResponseEntity.ok(ApiResponse.error(401, "Tài khoản đã bị vô hiệu hóa."));
            }
            return ResponseEntity.ok(ApiResponse.success(
                    LoginResponse.builder()
                            .token(token)
                            .username(user.getUsername())
                            .displayName(user.getDisplayName())
                            .admin(user.getIsAdmin())
                            .expiresAt(parsed.expMs())
                            .build(),
                    "OK"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(401, e.getMessage()));
        }
    }

    /**
     * POST /api/tools/auth/change-password
     * Đường dẫn vẫn thuộc /api/tools/auth (public), tự đọc token trong header.
     * Nếu token hỏng → 401 chứ không nhờ ToolsAuthFilter chặn.
     */
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @RequestHeader(value = "Authorization", required = false) String header,
            @RequestBody ChangePasswordRequest req) {
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7).trim() : null;
        var parsed = tokenService.parse(token);
        if (parsed == null) {
            return ResponseEntity.ok(ApiResponse.error(401, "Phiên đăng nhập đã hết hạn."));
        }
        try {
            userService.changeOwnPassword(parsed.username(), req);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã đổi mật khẩu"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Tools][Auth] change-password error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Đổi mật khẩu thất bại."));
        }
    }

    /**
     * GET /api/tools/auth/me — cho FE biết mình là ai (tiện cho UserMenu).
     * Nằm ở PUBLIC_PREFIXES nên tự đọc header, tự parse.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<LoginResponse>> me(@RequestHeader(value = "Authorization", required = false) String header) {
        return verify(header);
    }
}
