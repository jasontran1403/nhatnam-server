package com.nhatnam.server.tools.vmb.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.config.ToolsAuthContext;
import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.service.LookupService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint tab Tra cứu.
 *
 * ── PUBLIC ────────────────────────────────────────────────────
 *   GET    /api/tools/vmb/lookup                — search (q, type)
 *   POST   /api/tools/vmb/lookup                — tạo mới
 *   PUT    /api/tools/vmb/lookup/{id}           — sửa
 *   DELETE /api/tools/vmb/lookup/{id}           — xóa
 *   POST   /api/tools/vmb/lookup/{id}/reveal    — reveal password ({ "code": "123456" })
 *   GET    /api/tools/vmb/lookup/status         — trạng thái khóa/còn bao lần
 *
 * ── ADMIN (cần token admin của khu Tools) ─────────────────────
 *   POST   /api/tools/vmb/admin/lookup/unlock   — mở khóa 2FA
 *
 * Endpoint /admin nằm dưới prefix /api/tools/vmb/admin nên ToolsAuthFilter chặn
 * lại (xem cập nhật ở ToolsAuthFilter: PROTECTED_PREFIXES kiểm trước PUBLIC).
 */
@RestController
@RequestMapping("/api/tools/vmb")
@RequiredArgsConstructor
@Log4j2
public class LookupController {

    private final LookupService service;

    // ── PUBLIC ────────────────────────────────────────────────

    @GetMapping("/lookup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type) {
        try {
            var paged = service.search(q, type, page, size);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("content",       paged.getContent().stream().map(VmbMapper::toLookupIO).toList());
            res.put("totalElements", paged.getTotalElements());
            res.put("totalPages",    paged.getTotalPages());
            res.put("currentPage",   paged.getNumber());
            return ResponseEntity.ok(ApiResponse.success(res, "OK"));
        } catch (Exception e) {
            log.error("[VMB] list lookup", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được."));
        }
    }

    @PostMapping("/lookup")
    public ResponseEntity<ApiResponse<LookupIO>> create(@RequestBody SaveLookupRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toLookupIO(service.create(req)), "Đã tạo"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] create lookup", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo thất bại."));
        }
    }

    @PutMapping("/lookup/{id}")
    public ResponseEntity<ApiResponse<LookupIO>> update(@PathVariable Long id, @RequestBody SaveLookupRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toLookupIO(service.update(id, req)), "Đã cập nhật"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] update lookup {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    @DeleteMapping("/lookup/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (Exception e) {
            log.error("[VMB] delete lookup {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }

    /**
     * Reveal password. LUÔN trả 200 OK cùng ApiResponse — state trong body
     * (OK|LOCKED|RATE_LIMITED|INVALID_CODE) để FE xử lý UI mà không phải
     * check status code hay parse message.
     */
    @PostMapping("/lookup/{id}/reveal")
    public ResponseEntity<ApiResponse<RevealPasswordResponse>> reveal(
            @PathVariable Long id, @RequestBody RevealPasswordRequest req,
            HttpServletRequest request) {
        try {
            // Có thể null nếu người dùng chưa đăng nhập tools → dùng fallback secret ở yml
            String username = ToolsAuthContext.usernameOrNull(request);
            return ResponseEntity.ok(ApiResponse.success(service.reveal(id, req.getCode(), username), "OK"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] reveal lookup {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Lỗi máy chủ."));
        }
    }

    @GetMapping("/lookup/status")
    public ResponseEntity<ApiResponse<RevealPasswordResponse>> status() {
        return ResponseEntity.ok(ApiResponse.success(service.status(), "OK"));
    }

    // ── ADMIN ONLY ────────────────────────────────────────────

    /**
     * Mở khóa 2FA (khi khách hàng gõ sai 5 lần bị khóa 24h). Chỉ admin của khu
     * Tools mới gọi được — ToolsAuthFilter đã enforce, thêm ToolsAuthContext
     * kiểm quyền admin cụ thể.
     */
    @PostMapping("/admin/lookup/unlock")
    public ResponseEntity<ApiResponse<Void>> unlock(HttpServletRequest request) {
        try {
            ToolsAuthContext.requireAdmin(request);
            service.unlockNow();
            return ResponseEntity.ok(ApiResponse.success(null, "Đã mở khóa"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(403, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] admin unlock", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Lỗi máy chủ."));
        }
    }
}
