package com.nhatnam.server.tools.vmb.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.service.PassengerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint tab Thông tin khách.
 *
 * ── Passenger ─────────────────────────────────────────────────
 *   GET    /api/tools/vmb/passengers            — search + filter theo companyId
 *   GET    /api/tools/vmb/passengers/{id}       — chi tiết
 *   POST   /api/tools/vmb/passengers            — tạo mới (JSON)
 *   PUT    /api/tools/vmb/passengers/{id}       — cập nhật (JSON)
 *   DELETE /api/tools/vmb/passengers/{id}       — xóa
 *
 * ── Giấy tờ (2026-09-15) ───────────────────────────────────────
 *   PUT    /api/tools/vmb/passengers/{id}/documents/{type}   — upsert (multipart)
 *   DELETE /api/tools/vmb/passengers/{id}/documents/{type}   — xóa hoàn toàn
 *   type ∈ {cccd, passport} (case-insensitive)
 *
 *   Multipart form fields: docNumber, nationality (chỉ passport), issueDate,
 *   expiryDate, file (tùy chọn, chỉ đưa vào khi thay ảnh).
 *
 * ── Thẻ thành viên ───────────────────────────────────────────
 *   GET    /api/tools/vmb/passengers/{id}/membership-cards
 *   POST   /api/tools/vmb/passengers/{id}/membership-cards
 *   PUT    /api/tools/vmb/membership-cards/{id}
 *   DELETE /api/tools/vmb/membership-cards/{id}
 */
@RestController
@RequestMapping("/api/tools/vmb")
@RequiredArgsConstructor
@Log4j2
public class PassengerController {

    private final PassengerService service;

    // ── PASSENGERS ─────────────────────────────────────────────

    @GetMapping("/passengers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long companyId) {
        try {
            var paged = service.search(q, companyId, page, size);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("content",       paged.getContent().stream().map(VmbMapper::toPassengerIO).toList());
            res.put("totalElements", paged.getTotalElements());
            res.put("totalPages",    paged.getTotalPages());
            res.put("currentPage",   paged.getNumber());
            return ResponseEntity.ok(ApiResponse.success(res, "OK"));
        } catch (Exception e) {
            log.error("[VMB] list passengers", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được danh sách."));
        }
    }

    @GetMapping("/passengers/{id}")
    public ResponseEntity<ApiResponse<PassengerIO>> get(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toPassengerIO(service.findFull(id)), "OK"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] get passenger {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Lỗi máy chủ."));
        }
    }

    @PostMapping("/passengers")
    public ResponseEntity<ApiResponse<PassengerIO>> create(@RequestBody SavePassengerRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toPassengerIO(service.create(req)), "Đã tạo"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] create passenger", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo thất bại."));
        }
    }

    @PutMapping("/passengers/{id}")
    public ResponseEntity<ApiResponse<PassengerIO>> update(@PathVariable Long id, @RequestBody SavePassengerRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toPassengerIO(service.update(id, req)), "Đã cập nhật"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] update passenger {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    @DeleteMapping("/passengers/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (Exception e) {
            log.error("[VMB] delete passenger {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }

    // ── DOCUMENTS ──────────────────────────────────────────────

    /**
     * Upsert 1 giấy tờ. FE có thể chỉ gửi text (cập nhật số/ngày) hoặc gửi
     * kèm file để thay ảnh. Type ở path: "cccd" | "passport".
     */
    @PutMapping(value = "/passengers/{id}/documents/{type}", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<PassengerDocumentIO>> upsertDocument(
            @PathVariable Long id,
            @PathVariable String type,
            @RequestParam(value = "docNumber",   required = false) String docNumber,
            @RequestParam(value = "nationality", required = false) String nationality,
            @RequestParam(value = "issueDate",   required = false) String issueDate,
            @RequestParam(value = "expiryDate",  required = false) String expiryDate,
            @RequestParam(value = "file",        required = false) MultipartFile file) {
        try {
            var req = new SavePassengerDocumentRequest(docNumber, nationality, issueDate, expiryDate);
            var d = service.upsertDocument(id, type, req, file);
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toPassengerDocumentIO(d), "Đã lưu giấy tờ"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] upsert document {}/{}", id, type, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Lưu giấy tờ thất bại."));
        }
    }

    @DeleteMapping("/passengers/{id}/documents/{type}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(
            @PathVariable Long id, @PathVariable String type) {
        try {
            service.deleteDocument(id, type);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa giấy tờ"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] delete document {}/{}", id, type, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }

    // ── MEMBERSHIP CARDS ───────────────────────────────────────

    @GetMapping("/passengers/{id}/membership-cards")
    public ResponseEntity<ApiResponse<List<MembershipCardIO>>> listCards(@PathVariable Long id) {
        try {
            var res = service.listCards(id).stream().map(VmbMapper::toMembershipIO).toList();
            return ResponseEntity.ok(ApiResponse.success(res, "OK"));
        } catch (Exception e) {
            log.error("[VMB] list cards {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Lỗi máy chủ."));
        }
    }

    @PostMapping("/passengers/{id}/membership-cards")
    public ResponseEntity<ApiResponse<MembershipCardIO>> createCard(
            @PathVariable Long id, @RequestBody MembershipCardIO in) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toMembershipIO(service.createCard(id, in)), "Đã tạo"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] create card {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo thẻ thất bại."));
        }
    }

    @PutMapping("/membership-cards/{id}")
    public ResponseEntity<ApiResponse<MembershipCardIO>> updateCard(
            @PathVariable Long id, @RequestBody MembershipCardIO in) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toMembershipIO(service.updateCard(id, in)), "Đã cập nhật"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] update card {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    @DeleteMapping("/membership-cards/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCard(@PathVariable Long id) {
        try {
            service.deleteCard(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (Exception e) {
            log.error("[VMB] delete card {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }
}