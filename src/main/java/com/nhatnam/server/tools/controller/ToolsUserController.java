package com.nhatnam.server.tools.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.config.ToolsAuthContext;
import com.nhatnam.server.tools.dto.ToolsAuthDtos.*;
import com.nhatnam.server.tools.service.ToolsUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Quản lý danh sách tài khoản tools — chỉ admin.
 *
 * Endpoint TOTP:
 *   GET  /api/tools/users/{id}/totp-secret       — xem secret hiện tại
 *   POST /api/tools/users/{id}/totp-secret/regenerate  — sinh secret mới
 * Cả 2 đều bắt buộc admin — người dùng thường không nhìn thấy secret của
 * người khác, chỉ dùng authenticator app đã cấu hình từ trước.
 */
@RestController
@RequestMapping("/api/tools/users")
@RequiredArgsConstructor
@Log4j2
public class ToolsUserController {

    private final ToolsUserService service;

    @GetMapping
    public ResponseEntity<ApiResponse<Object>> list(HttpServletRequest request) {
        try {
            ToolsAuthContext.requireAdmin(request);
            return ResponseEntity.ok(ApiResponse.success(service.listAll(), "OK"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(403, e.getMessage()));
        } catch (Exception e) {
            log.error("[Tools][Users] list error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được danh sách tài khoản."));
        }
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserView>> create(HttpServletRequest request,
                                                        @RequestBody CreateUserRequest req) {
        try {
            ToolsAuthContext.requireAdmin(request);
            return ResponseEntity.ok(ApiResponse.success(service.create(req), "Đã tạo tài khoản"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Tools][Users] create error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo tài khoản thất bại."));
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<UserView>> update(HttpServletRequest request,
                                                       @PathVariable Long id,
                                                       @RequestBody UpdateUserRequest req) {
        try {
            ToolsAuthContext.requireAdmin(request);
            String me = ToolsAuthContext.username(request);
            return ResponseEntity.ok(ApiResponse.success(
                    service.update(id, req, me, true), "Đã cập nhật"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Tools][Users] update error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(HttpServletRequest request, @PathVariable Long id) {
        try {
            ToolsAuthContext.requireAdmin(request);
            String me = ToolsAuthContext.username(request);
            service.delete(id, me);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Tools][Users] delete error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }

    // ── TOTP secret (admin only) ────────────────────────────────

    /**
     * Trả về secret hiện tại. Admin dùng để hiển thị cho user quét vào
     * Google Authenticator lần đầu, hoặc để xem lại khi user báo đã mất.
     */
    @GetMapping("/{id}/totp-secret")
    public ResponseEntity<ApiResponse<Map<String, String>>> getTotpSecret(HttpServletRequest request,
                                                                          @PathVariable Long id) {
        try {
            ToolsAuthContext.requireAdmin(request);
            String secret = service.getTotpSecret(id);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "secret", secret == null ? "" : secret
            ), "OK"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(403, e.getMessage()));
        } catch (Exception e) {
            log.error("[Tools][Users] get secret error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không lấy được secret."));
        }
    }

    /**
     * Sinh secret mới. User phải cấu hình lại app authenticator ngay — code
     * cũ hết tác dụng lập tức.
     */
    @PostMapping("/{id}/totp-secret/regenerate")
    public ResponseEntity<ApiResponse<Map<String, String>>> regenerateTotpSecret(HttpServletRequest request,
                                                                                 @PathVariable Long id) {
        try {
            ToolsAuthContext.requireAdmin(request);
            String secret = service.regenerateTotpSecret(id);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "secret", secret
            ), "Đã xoay secret mới"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Tools][Users] regen secret error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xoay secret thất bại."));
        }
    }
}
