package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.pos.PosOrder;
import com.nhatnam.server.repository.pos.PosOrderRepository;
import com.nhatnam.server.repository.pos.PosOrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.util.*;

/**
 * Controller public (không cần auth) để:
 *  - Tra cứu đơn hàng qua invoice_token (UUID ngẫu nhiên từ QR bill)
 *  - Submit thông tin xuất hóa đơn (tên công ty, MST, địa chỉ, email)
 *
 * Base path: /api/public/invoice
 *
 * Flow:
 *   1. Flutter in bill → tạo QR với URL: https://www.original-taste.vn/invoice/{invoiceToken}
 *   2. Khách quét QR → GET /api/public/invoice/{token}   → xem thông tin đơn
 *   3. Khách điền form → POST /api/public/invoice/{token} → lưu thông tin
 */
@RestController
@RequestMapping("/api/public/invoice")
@RequiredArgsConstructor
@Log4j2
public class PublicInvoiceController {

    private final PosOrderRepository     posOrderRepo;
    private final PosOrderItemRepository posOrderItemRepo;  // nếu có

    private static final ZoneId VN_ZONE   = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final long   DEADLINE_MS = 6L * 3600_000L;   // 6 giờ

    // ─────────────────────────────────────────────────────────────────
    // GET /api/public/invoice/{token}
    // Tra cứu đơn hàng bằng invoice_token (không cần auth)
    // ─────────────────────────────────────────────────────────────────
    @GetMapping("/{token}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOrderByToken(
            @PathVariable String token) {
        try {
            PosOrder order = posOrderRepo.findByInvoiceToken(token)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng"));

            return ResponseEntity.ok(ApiResponse.success(toPublicMap(order), "OK"));
        } catch (Exception e) {
            log.warn("[PublicInvoice] token not found: {}", token);
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // POST /api/public/invoice/{token}
    // Submit thông tin xuất hóa đơn
    // Body: { taxCode, companyName, address, invoiceEmail }
    // ─────────────────────────────────────────────────────────────────
    @PostMapping("/{token}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitInvoiceInfo(
            @PathVariable String token,
            @RequestBody InvoiceSubmitRequest req) {
        try {
            PosOrder order = posOrderRepo.findByInvoiceToken(token)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng"));

            // ── Kiểm tra deadline 6h ─────────────────────────────────
            long elapsed = System.currentTimeMillis() - order.getCreatedAt();
            if (elapsed > DEADLINE_MS) {
                return ResponseEntity.ok(ApiResponse.error(410,
                        "Đơn hàng đã quá 6 giờ, không thể nhập thông tin xuất hóa đơn"));
            }

            // ── Validate ─────────────────────────────────────────────
            if (req.getTaxCode() == null || req.getTaxCode().isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu mã số thuế"));
            if (req.getCompanyName() == null || req.getCompanyName().isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu tên công ty"));
            if (req.getInvoiceEmail() == null || req.getInvoiceEmail().isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu email nhận hóa đơn"));

            // ── Lưu thông tin ─────────────────────────────────────────
            order.setInvoiceTaxCode(req.getTaxCode().trim());
            order.setInvoiceCompanyName(req.getCompanyName().trim());
            // address là optional — PosOrder cần thêm field này nếu chưa có
             order.setInvoiceAddress(req.getAddress() != null ? req.getAddress().trim() : null);
            order.setInvoiceEmail(req.getInvoiceEmail().trim());
            order.setInvoiceSubmittedAt(System.currentTimeMillis());
            order.setUpdatedAt(System.currentTimeMillis());
            posOrderRepo.save(order);

            log.info("[PublicInvoice] submitted: token={}, taxCode={}, email={}",
                    token, req.getTaxCode(), req.getInvoiceEmail());

            return ResponseEntity.ok(ApiResponse.success(toPublicMap(order), "Gửi thành công"));
        } catch (Exception e) {
            log.error("[PublicInvoice] submit error token={}", token, e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helper: map PosOrder → JSON an toàn (không lộ internal fields)
    // ─────────────────────────────────────────────────────────────────
    private Map<String, Object> toPublicMap(PosOrder o) {
        long elapsed = o.getCreatedAt() != null
                ? System.currentTimeMillis() - o.getCreatedAt() : Long.MAX_VALUE;

        Map<String, Object> m = new LinkedHashMap<>();

        // ── Thông tin đơn (safe to expose) ───────────────────────────
        m.put("orderCode",      o.getOrderCode());
        m.put("appOrderCode",   o.getAppOrderCode());
        m.put("orderSource",    o.getOrderSource());
        m.put("paymentMethod",  o.getPaymentMethod());
        m.put("status",         o.getStatus());

        // Store
        m.put("storeName",  o.getStore() != null ? o.getStore().getName()    : null);
        m.put("storePhone", o.getStore() != null ? o.getStore().getPhone()   : null);

        // Khách hàng
        m.put("customerName",  o.getCustomerName());
        m.put("customerPhone", o.getCustomerPhone());

        // Tiền
        m.put("totalAmount",    o.getTotalAmount());
        m.put("discountAmount", o.getDiscountAmount());
        m.put("totalVatAmount", o.getTotalVatAmount());
        m.put("finalAmount",    o.getFinalAmount());
        m.put("createdAt",      o.getCreatedAt());

        // ── Trạng thái deadline ───────────────────────────────────────
        m.put("deadlineExpired",   elapsed > DEADLINE_MS);
        m.put("deadlineMs",        DEADLINE_MS);
        m.put("elapsedMs",         elapsed);

        // ── Thông tin hóa đơn đã submit ───────────────────────────────
        boolean submitted = o.getInvoiceTaxCode() != null && !o.getInvoiceTaxCode().isBlank();
        m.put("invoiceSubmitted", submitted);
        if (submitted) {
            m.put("invoiceTaxCode",     o.getInvoiceTaxCode());
            m.put("invoiceCompanyName", o.getInvoiceCompanyName());
            m.put("invoiceEmail",       o.getInvoiceEmail());
            m.put("invoiceSubmittedAt", o.getInvoiceSubmittedAt());
             m.put("invoiceAddress",  o.getInvoiceAddress()); // thêm nếu có field
        }

        // ── Kết quả hóa đơn điện tử (nếu đã phát hành) ───────────────
        m.put("eInvoiceNo",          o.getEInvoiceNo());
        m.put("eInvoiceStatus",      o.getEInvoiceStatus());
        m.put("eInvoiceIssuedDate",  o.getEInvoiceIssuedDate());
        // PDF URL: chỉ expose nếu đã ISSUED
        if ("ISSUED".equals(o.getEInvoiceStatus())) {
            m.put("eInvoicePdfUrl", o.getEInvoicePdfUrl());
        }

        // ── Items ─────────────────────────────────────────────────────
        // Load lazy nếu cần (nếu fetch EAGER thì bỏ qua)
        try {
            if (o.getItems() != null) {
                var items = o.getItems().stream().map(item -> {
                    Map<String, Object> i = new LinkedHashMap<>();
                    i.put("productName",    item.getProductName());
                    i.put("quantity",       item.getQuantity());
                    i.put("finalUnitPrice", item.getFinalUnitPrice());
                    i.put("subtotal",       item.getSubtotal());
                    i.put("vatRate",        item.getVatPercent());
                    return i;
                }).toList();
                m.put("items", items);
            }
        } catch (Exception ignored) {
            // Lazy load có thể fail ngoài transaction — bỏ qua
            m.put("items", List.of());
        }

        return m;
    }

    // ─────────────────────────────────────────────────────────────────
    // DTO
    // ─────────────────────────────────────────────────────────────────
    @lombok.Data
    public static class InvoiceSubmitRequest {
        private String taxCode;
        private String companyName;
        private String address;        // optional
        private String invoiceEmail;
    }
}