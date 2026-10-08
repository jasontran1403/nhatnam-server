package com.nhatnam.server.tools.vmb.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.enumtype.VmbFeeType;
import com.nhatnam.server.tools.vmb.service.BookingProofService;
import com.nhatnam.server.tools.vmb.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint cho tab Vé máy bay.
 *
 * ── Endpoints mới (2026-09-19) ──────────────────────────────
 *   GET    /api/tools/vmb/fee-types              — dropdown loại phí VN
 *
 *   POST   /api/tools/vmb/bookings/{id}/proofs   — upload 1 file bằng chứng
 *                                                   (query param ?ticketId=X
 *                                                   để gắn ticket; bỏ qua để
 *                                                   gắn booking-level)
 *   DELETE /api/tools/vmb/proofs/{proofId}       — xóa 1 file bằng chứng
 *
 * ── Endpoint hóa đơn cập nhật ─────────────────────────────
 *   POST/PUT /invoices     — thay {@code ticketId} single bằng {@code ticketIds}
 *                             (repeated param). Empty = chung cả booking.
 */
@RestController
@RequestMapping("/api/tools/vmb")
@RequiredArgsConstructor
@Log4j2
public class BookingController {

    private final BookingService service;
    private final BookingProofService proofService;

    // ── FEE TYPES (dropdown) ───────────────────────────────────

    @GetMapping("/fee-types")
    public ResponseEntity<ApiResponse<List<FeeTypeIO>>> feeTypes() {
        List<FeeTypeIO> list = VmbFeeType.LABELS_VI.entrySet().stream()
                .map(e -> FeeTypeIO.builder().code(e.getKey()).label(e.getValue()).build())
                .toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    // ── BOOKINGS ───────────────────────────────────────────────

    @GetMapping("/bookings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long fromSale,
            @RequestParam(required = false) Long toSale) {
        try {
            var paged = service.list(q, fromSale, toSale, page, size);
            var content = paged.getContent().stream().map(VmbMapper::toBookingIO).toList();
            service.enrichCompanies(content);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("content",       content);
            res.put("totalElements", paged.getTotalElements());
            res.put("totalPages",    paged.getTotalPages());
            res.put("currentPage",   paged.getNumber());
            return ResponseEntity.ok(ApiResponse.success(res, "OK"));
        } catch (Exception e) {
            log.error("[VMB] list bookings", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được danh sách vé."));
        }
    }

    @GetMapping("/bookings/totals")
    public ResponseEntity<ApiResponse<List<BookingTotals>>> totals(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long fromSale,
            @RequestParam(required = false) Long toSale) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.totals(q, fromSale, toSale), "OK"));
        } catch (Exception e) {
            log.error("[VMB] totals", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tính được tổng."));
        }
    }

    @GetMapping("/bookings/{id}")
    public ResponseEntity<ApiResponse<BookingIO>> get(@PathVariable Long id) {
        try {
            var b = VmbMapper.toBookingIO(service.findFull(id));
            service.enrichCompanies(List.of(b));
            return ResponseEntity.ok(ApiResponse.success(b, "OK"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] get booking {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Lỗi máy chủ."));
        }
    }

    @PostMapping("/bookings")
    public ResponseEntity<ApiResponse<BookingIO>> create(@RequestBody SaveBookingRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toBookingIO(service.create(req)), "Đã tạo booking"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] create booking", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo booking thất bại."));
        }
    }

    @PutMapping("/bookings/{id}")
    public ResponseEntity<ApiResponse<BookingIO>> update(@PathVariable Long id, @RequestBody SaveBookingRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toBookingIO(service.update(id, req)), "Đã cập nhật"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] update booking {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    @DeleteMapping("/bookings/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (Exception e) {
            log.error("[VMB] delete booking {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }

    // ── MẶT VÉ (booking-level face) ──────────────────────────

    @PostMapping(value = "/bookings/{id}/face", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<TicketFileIO>> uploadBookingFace(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toTicketFileIO(service.uploadBookingFace(id, file)),
                    "Đã tải mặt vé chung"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] upload booking face {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tải file thất bại."));
        }
    }

    @DeleteMapping("/bookings/{id}/face")
    public ResponseEntity<ApiResponse<Void>> deleteBookingFace(@PathVariable Long id) {
        try {
            service.deleteBookingFace(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] delete booking face {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }

    // ── MẶT VÉ (ticket-level face) ───────────────────────────

    @PostMapping(value = "/tickets/{id}/face", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<TicketFileIO>> uploadTicketFace(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toTicketFileIO(service.uploadTicketFace(id, file)),
                    "Đã tải mặt vé"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] upload ticket face {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tải file thất bại."));
        }
    }

    @DeleteMapping("/tickets/{id}/face")
    public ResponseEntity<ApiResponse<Void>> deleteTicketFace(@PathVariable Long id) {
        try {
            service.deleteTicketFace(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] delete ticket face {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }

    // ── BOOKING PROOFS (file bằng chứng) ─────────────────────

    /**
     * Upload 1 file bằng chứng.
     *   ticketId = null → chung cả booking
     *   ticketId != null → riêng khách đó
     */
    @PostMapping(value = "/bookings/{id}/proofs", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<BookingProofIO>> uploadProof(
            @PathVariable Long id,
            @RequestParam(value = "ticketId", required = false) Long ticketId,
            @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    VmbMapper.toBookingProofIO(proofService.upload(id, ticketId, file)),
                    "Đã tải bằng chứng"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] upload proof booking={} ticket={}", id, ticketId, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tải file thất bại."));
        }
    }

    @DeleteMapping("/proofs/{proofId}")
    public ResponseEntity<ApiResponse<Void>> deleteProof(@PathVariable Long proofId) {
        try {
            proofService.delete(proofId);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] delete proof {}", proofId, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }

    // ── PAID STATUS ─────────────────────────────────────────

    @PatchMapping("/tickets/{id}/paid")
    public ResponseEntity<ApiResponse<Void>> setPaid(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            service.setPaidStatus(id, body.getOrDefault("status", "PENDING"));
            return ResponseEntity.ok(ApiResponse.success(null, "OK"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] set paid status {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    // ── INVOICES ────────────────────────────────────────────

    /**
     * Tạo hóa đơn.
     * Params:
     *   status       : DRAFT | ISSUED | ADJUSTED
     *   note         : optional
     *   ticketIds    : repeated param (VD ?ticketIds=1&ticketIds=2). Không truyền = chung cả booking.
     *   draftFile / issuedFile / adjustmentFile / adjustmentRecordFile : file tương ứng
     */
    @PostMapping(value = "/bookings/{id}/invoices", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<InvoiceIO>> createInvoice(
            @PathVariable Long id,
            @RequestParam("status") String status,
            @RequestParam(value = "note",      required = false) String note,
            @RequestParam(value = "ticketIds", required = false) List<Long> ticketIds,
            @RequestParam(value = "draftFile",            required = false) MultipartFile draft,
            @RequestParam(value = "issuedFile",           required = false) MultipartFile issued,
            @RequestParam(value = "adjustmentFile",       required = false) MultipartFile adj,
            @RequestParam(value = "adjustmentRecordFile", required = false) MultipartFile adjRec) {
        try {
            var inv = service.createInvoice(id, ticketIds == null ? new ArrayList<>() : ticketIds,
                    status, note, draft, issued, adj, adjRec);
            return ResponseEntity.ok(ApiResponse.success(VmbMapper.toInvoiceIO(inv), "Đã tạo hóa đơn"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] create invoice", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo hóa đơn thất bại."));
        }
    }

    /**
     * Update hóa đơn. Nếu bỏ trống {@code ticketIds} thì KHÔNG đổi. Để đổi
     * sang "chung cả booking" (empty set), gửi param {@code clearTicketIds=1}.
     */
    @PutMapping(value = "/invoices/{id}", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<InvoiceIO>> updateInvoice(
            @PathVariable Long id,
            @RequestParam(value = "status",           required = false) String status,
            @RequestParam(value = "note",             required = false) String note,
            @RequestParam(value = "ticketIds",        required = false) List<Long> ticketIds,
            @RequestParam(value = "clearTicketIds",   required = false) String clearTicketIds,
            @RequestParam(value = "draftFile",            required = false) MultipartFile draft,
            @RequestParam(value = "issuedFile",           required = false) MultipartFile issued,
            @RequestParam(value = "adjustmentFile",       required = false) MultipartFile adj,
            @RequestParam(value = "adjustmentRecordFile", required = false) MultipartFile adjRec) {
        try {
            List<Long> effectiveIds;
            if ("1".equals(clearTicketIds) || "true".equalsIgnoreCase(clearTicketIds)) {
                effectiveIds = new ArrayList<>(); // đổi thành chung cả booking
            } else {
                effectiveIds = ticketIds; // null = không đổi
            }
            var inv = service.updateInvoice(id, effectiveIds, status, note, draft, issued, adj, adjRec);
            return ResponseEntity.ok(ApiResponse.success(VmbMapper.toInvoiceIO(inv), "Đã cập nhật"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] update invoice {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    @PutMapping(value = "/invoices/{id}/files/{slot}", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<InvoiceIO>> replaceInvoiceFile(
            @PathVariable Long id, @PathVariable String slot,
            @RequestParam("file") MultipartFile file) {
        try {
            var inv = service.replaceInvoiceFile(id, slot, file);
            return ResponseEntity.ok(ApiResponse.success(VmbMapper.toInvoiceIO(inv), "Đã thay file"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] replace invoice file {}/{}", id, slot, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Thay file thất bại."));
        }
    }

    @DeleteMapping("/invoices/{id}/files/{slot}")
    public ResponseEntity<ApiResponse<InvoiceIO>> deleteInvoiceFile(
            @PathVariable Long id, @PathVariable String slot) {
        try {
            var inv = service.deleteInvoiceFile(id, slot);
            return ResponseEntity.ok(ApiResponse.success(VmbMapper.toInvoiceIO(inv), "Đã xóa file"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] delete invoice file {}/{}", id, slot, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa file thất bại."));
        }
    }

    @DeleteMapping("/invoices/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteInvoice(@PathVariable Long id) {
        try {
            service.deleteInvoice(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] delete invoice {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }
}