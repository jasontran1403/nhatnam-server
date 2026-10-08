package com.nhatnam.server.tools.vmb.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Endpoint thanh toán.
 *
 *   POST   /bookings/{id}/payments          — thu 1 lần cho 1 booking (multipart)
 *   POST   /bookings/pay-batch              — thu batch nhiều booking (multipart)
 *   GET    /bookings/{id}/payments          — lịch sử thu của 1 booking
 *   DELETE /payments/{id}                   — xóa 1 phiếu thu
 */
@RestController
@RequestMapping("/api/tools/vmb")
@RequiredArgsConstructor
@Log4j2
public class PaymentController {

    private final PaymentService service;

    @PostMapping(value = "/bookings/{id}/payments", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<PaymentIO>> pay(
            
            @PathVariable Long id,
            @RequestParam("amount") String amount,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        try {
            String user = resolveUsername();
            return ResponseEntity.ok(ApiResponse.success(
                    service.recordPayment(id, amount, note, file, user), "Đã ghi nhận"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] pay booking {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Ghi nhận thanh toán thất bại."));
        }
    }

    @PostMapping(value = "/bookings/pay-batch", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<List<PaymentIO>>> batch(
            
            @RequestParam("bookingIds") List<Long> bookingIds,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        try {
            String user = resolveUsername();
            return ResponseEntity.ok(ApiResponse.success(
                    service.batchPay(bookingIds, note, file, user), "Đã ghi nhận batch"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] batch pay {} bookings", bookingIds == null ? 0 : bookingIds.size(), e);
            return ResponseEntity.ok(ApiResponse.error(500, "Ghi nhận batch thất bại."));
        }
    }

    @GetMapping("/bookings/{id}/payments")
    public ResponseEntity<ApiResponse<List<PaymentIO>>> list(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.listByBooking(id), "OK"));
        } catch (Exception e) {
            log.error("[VMB] list payments {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được lịch sử thu."));
        }
    }

    @DeleteMapping("/payments/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            service.deletePayment(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa phiếu thu"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] delete payment {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa phiếu thu thất bại."));
        }
    }

    /** Lấy username hiện tại từ SecurityContext; null nếu không có / lỗi. */
    private static String resolveUsername() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null ? auth.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
