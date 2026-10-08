package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.einvoice.service.SaleInvoiceInfoService;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public (không cần auth) — khách quét QR trên hóa đơn bán sỉ/lẻ để tự nhập
 * thông tin xuất hóa đơn.
 *
 * Base path: /api/public/invoice/sale
 * Đã nằm trong WHITE_LIST_URL (/api/public/**) nên không cần sửa security.
 *
 * Không đụng tới /api/public/invoice/{token} của POS: path POS chỉ có 1 segment
 * sau base, path này có 2 nên Spring phân biệt được.
 *
 * Khác POS: đơn sỉ/lẻ KHÔNG giới hạn 6 giờ. Chỉ khóa khi hóa đơn đã phát hành.
 */
@RestController
@RequestMapping("/api/public/invoice/sale")
@RequiredArgsConstructor
@Log4j2
public class PublicSaleInvoiceController {

    private final OrderRepository        orderRepo;
    private final SaleInvoiceInfoService invoiceInfoService;

    /** GET /api/public/invoice/sale/{token} — xem đơn + thông tin đã nhập */
    @GetMapping("/{token}")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> getByToken(@PathVariable String token) {
        try {
            Order order = orderRepo.findByInvoiceToken(token)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng"));
            return ResponseEntity.ok(ApiResponse.success(toPublicMap(order), "OK"));
        } catch (Exception e) {
            log.warn("[PublicSaleInvoice] token not found: {}", token);
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        }
    }

    /** POST /api/public/invoice/sale/{token} — khách gửi/cập nhật thông tin */
    @PostMapping("/{token}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @PathVariable String token,
            @RequestBody SaleInvoiceInfoService.InvoiceInfoRequest req) {
        try {
            Order order = orderRepo.findByInvoiceToken(token)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng"));

            Order saved = invoiceInfoService.apply(order, req);
            return ResponseEntity.ok(ApiResponse.success(toPublicMap(saved), "Gửi thành công"));

        } catch (IllegalStateException e) {
            // Đã phát hành hóa đơn → 410 Gone về mặt nghiệp vụ
            return ResponseEntity.ok(ApiResponse.error(410, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[PublicSaleInvoice] submit error token={}", token, e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Chỉ expose field an toàn cho public — không lộ id, user, giá vốn...
    // ─────────────────────────────────────────────────────────────────
    private Map<String, Object> toPublicMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderCode",      o.getOrderCode());
        m.put("type",           o.getType());
        m.put("typeLabel",      typeLabel(o.getType()));
        m.put("status",         o.getStatus());
        m.put("paymentMethod",  o.getPaymentMethod());
        m.put("customerName",   o.getCustomerName());
        m.put("customerPhone",  o.getCustomerPhone());
        m.put("createdAt",      o.getCreatedAt());

        m.put("subtotal",       o.getSubtotal());
        m.put("discountAmount", o.getDiscountAmount());
        m.put("discountRate",   o.getDiscountRate());
        m.put("totalVatAmount", o.getVatAmount());
        m.put("finalAmount",    o.getFinalAmount());

        String invoiceEmail = notBlank(o.getInvoiceEmail()) ? o.getInvoiceEmail() : o.getCustomerEmail();
        boolean submitted = notBlank(o.getTaxCode()) || notBlank(invoiceEmail);
        m.put("invoiceSubmitted",   submitted);
        m.put("invoiceTaxCode",     o.getTaxCode());
        m.put("invoiceCompanyName", o.getCompanyName());
        m.put("invoiceAddress",     o.getCompanyAddress());
        m.put("invoiceEmail",       invoiceEmail);
        m.put("contactName",        o.getContactName());
        m.put("companyPhone",       o.getCompanyPhone());
        m.put("invoiceSubmittedAt", o.getInvoiceSubmittedAt());

        m.put("eInvoiceNo",         o.getEInvoiceNo());
        m.put("eInvoiceStatus",     o.getEInvoiceStatus());
        m.put("eInvoiceIssuedDate", o.getEInvoiceIssuedDate());
        // Khóa form khi đã phát hành
        m.put("locked", "ISSUED".equals(o.getEInvoiceStatus()));

        try {
            List<OrderItem> items = o.getOrderItems();
            if (items != null) {
                m.put("items", items.stream().map(it -> {
                    Map<String, Object> i = new LinkedHashMap<>();
                    i.put("productName", it.getProductName());
                    i.put("variantName", it.getVariantName());
                    i.put("unit",        it.getUnit());
                    i.put("quantity",    it.getQuantity());
                    i.put("unitPrice",   it.getUnitPrice());
                    i.put("subtotal",    it.getSubtotal());
                    i.put("vatRate",     it.getVatRate());
                    return i;
                }).toList());
            }
        } catch (Exception ignored) {
            m.put("items", List.of());
        }

        return m;
    }

    private static String typeLabel(String type) {
        if (type == null) return null;
        return switch (type.trim().toUpperCase()) {
            case "WHOLESALE", "SI", "SỈ" -> "Sỉ";
            case "RETAIL", "LE", "LẺ"    -> "Lẻ";
            default -> type;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
