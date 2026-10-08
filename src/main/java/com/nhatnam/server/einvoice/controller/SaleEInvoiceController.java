package com.nhatnam.server.einvoice.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.einvoice.EInvoiceException;
import com.nhatnam.server.einvoice.dto.EInvoiceRequestDto.BusinessBuyerInfo;
import com.nhatnam.server.einvoice.dto.EInvoiceRequestDto.EInvoiceResult;
import com.nhatnam.server.einvoice.service.SaleInvoiceInfoService;
import com.nhatnam.server.einvoice.service.SaleInvoiceTokenService;
import com.nhatnam.server.einvoice.service.SaleOrderEInvoiceService;
import com.nhatnam.server.einvoice.service.ViettelEInvoiceService;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hóa đơn điện tử cho ĐƠN SỈ/LẺ (tab "Bán sỉ/lẻ").
 * Base path: /api/pos/einvoice/sale
 *
 * Nằm dưới /api/pos/einvoice/** nên kế thừa luôn rule bảo mật đã có trong
 * SecurityConfiguration: chỉ ACCOUNTANT + SUPERADMIN.
 *
 * Không đụng gì tới EInvoiceController (POS) — hai luồng độc lập, chỉ dùng
 * chung phần ký số / gọi Viettel trong ViettelEInvoiceService.
 *
 * Lưu ý: các endpoint phát hành / preview được đánh dấu @Transactional vì
 * Order.orderItems là LAZY và cần được nạp khi build request hóa đơn.
 */
@RestController
@RequestMapping("/api/pos/einvoice/sale")
@RequiredArgsConstructor
@Log4j2
public class SaleEInvoiceController {

    private final OrderRepository          orderRepo;
    private final SaleOrderEInvoiceService saleInvoiceService;
    private final ViettelEInvoiceService einvoiceService;
    private final SaleInvoiceInfoService   invoiceInfoService;
    private final SaleInvoiceTokenService  tokenService;

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    // ─────────────────────────────────────────────────────────────────
    // Danh sách đơn
    // ─────────────────────────────────────────────────────────────────

    /**
     * GET /api/pos/einvoice/sale/orders
     * Params: date | (fromDate & toDate), type, q, page, size
     */
    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> listOrders(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            LocalDate ldFrom, ldTo;
            if (notBlank(fromDate) && notBlank(toDate)) {
                ldFrom = LocalDate.parse(fromDate);
                ldTo   = LocalDate.parse(toDate);
            } else {
                ldFrom = ldTo = notBlank(date) ? LocalDate.parse(date) : LocalDate.now(VN_ZONE);
            }
            long fromTs = ldFrom.atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
            long toTs   = ldTo.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;

            var pr = PageRequest.of(page, size);
            Page<Order> paged = orderRepo.findForEInvoice(
                    fromTs, toTs,
                    notBlank(type) ? type : null,
                    notBlank(q) ? q.trim() : null,
                    pr);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",       paged.getContent().stream().map(this::toOrderMap).toList());
            result.put("totalElements", paged.getTotalElements());
            result.put("totalPages",    paged.getTotalPages());
            result.put("currentPage",   paged.getNumber());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[EInvoice][Sale] listOrders error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    /** GET /api/pos/einvoice/sale/types — đổ dropdown lọc theo loại đơn */
    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> listTypes() {
        try {
            List<Map<String, String>> out = orderRepo.findDistinctTypes().stream()
                    .filter(SaleEInvoiceController::notBlank)
                    .map(t -> {
                        Map<String, String> m = new LinkedHashMap<String, String>();
                        m.put("value", t);
                        m.put("label", typeLabel(t));
                        return m;
                    })
                    .toList();
            return ResponseEntity.ok(ApiResponse.success(out, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Xem trước / phát hành
    // ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/pos/einvoice/sale/preview/{orderCode}
     * Body (optional) BusinessBuyerInfo — bỏ trống thì lấy thông tin từ đơn.
     */
    @PostMapping("/preview/{orderCode}")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<String>> preview(
            @PathVariable String orderCode,
            @RequestBody(required = false) BusinessBuyerInfo buyerInfo) {
        try {
            Order order = findOrder(orderCode);
            String pdfBase64 = saleInvoiceService.previewInvoice(order, nullIfEmpty(buyerInfo));
            return ResponseEntity.ok(ApiResponse.success(pdfBase64, "OK"));
        } catch (Exception e) {
            return handleEInvoiceError("preview", orderCode, e);
        }
    }

    /**
     * POST /api/pos/einvoice/sale/issue/{orderCode}
     * Phát hành hóa đơn. Tự nhận diện có MST (doanh nghiệp) hay không (cá nhân).
     */
    @PostMapping("/issue/{orderCode}")
    @Transactional
    public ResponseEntity<ApiResponse<EInvoiceResult>> issue(
            @PathVariable String orderCode,
            @RequestBody(required = false) BusinessBuyerInfo buyerInfo) {
        try {
            Order order = findOrder(orderCode);

            if ("ISSUED".equals(order.getEInvoiceStatus()) && notBlank(order.getEInvoiceNo())) {
                return ResponseEntity.ok(ApiResponse.error(400,
                        "Đơn đã có hóa đơn: " + order.getEInvoiceNo()));
            }

            EInvoiceResult result = saleInvoiceService.issueInvoice(order, nullIfEmpty(buyerInfo));
            if ("ERROR".equals(result.getStatus())) {
                return ResponseEntity.ok(ApiResponse.error(400,
                        "Tạo hóa đơn thất bại: " + result.getErrorMessage()));
            }

            order.setEInvoiceNo(result.getInvoiceNo());
            order.setEInvoiceIssuedDate(result.getInvoiceIssuedDate() != null
                    ? result.getInvoiceIssuedDate() : System.currentTimeMillis());
            order.setEInvoiceStatus("ISSUED");
            order.setEInvoiceTemplateCode(result.getTemplateCode());
            order.setEInvoiceSeries(result.getInvoiceSeries());
            if (result.getTransactionID() != null) {
                order.setEInvoiceTransactionId(result.getTransactionID());
            }
            order.setUpdatedAt(System.currentTimeMillis());
            orderRepo.save(order);

            log.info("[EInvoice][Sale] issued orderCode={} invoiceNo={}", orderCode, result.getInvoiceNo());
            return ResponseEntity.ok(ApiResponse.success(result, "Tạo hóa đơn thành công"));
        } catch (Exception e) {
            return handleEInvoiceError("issue", orderCode, e);
        }
    }

    /** POST /api/pos/einvoice/sale/{orderCode}/send-cqt */
    @PostMapping("/{orderCode}/send-cqt")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendToCqt(@PathVariable String orderCode) {
        try {
            Order order = findOrder(orderCode);
            if (!notBlank(order.getEInvoiceNo())) {
                return ResponseEntity.ok(ApiResponse.error(400, "Đơn hàng chưa có hóa đơn được phát hành"));
            }
            Map<String, Object> result = einvoiceService.sendToCqt(
                    order.getEInvoiceNo(), order.getEInvoiceTransactionId());
            return ResponseEntity.ok(ApiResponse.success(result, "Gửi CQT thành công"));
        } catch (Exception e) {
            return handleEInvoiceError("sendToCqt", orderCode, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Thông tin xuất hóa đơn — kế toán sửa trực tiếp
    // ─────────────────────────────────────────────────────────────────

    /**
     * PUT /api/pos/einvoice/sale/{orderCode}/invoice-info
     * Body: { taxCode, companyName, companyAddress, invoiceEmail, contactName, companyPhone }
     * Bỏ trống taxCode + companyName = khách lẻ, chỉ cần email.
     */
    @PutMapping("/{orderCode}/invoice-info")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateInvoiceInfo(
            @PathVariable String orderCode,
            @RequestBody SaleInvoiceInfoService.InvoiceInfoRequest req) {
        try {
            Order order = findOrder(orderCode);
            Order saved = invoiceInfoService.apply(order, req);
            return ResponseEntity.ok(ApiResponse.success(toOrderMap(saved), "Cập nhật thành công"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[EInvoice][Sale] updateInvoiceInfo error orderCode={}", orderCode, e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    /**
     * GET /api/pos/einvoice/sale/{orderCode}/invoice-qr
     * Trả link công khai + URL ảnh QR để kế toán gửi lại cho khách.
     * Tự sinh token nếu đơn chưa có.
     */
    @GetMapping("/{orderCode}/invoice-qr")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> invoiceQr(@PathVariable String orderCode) {
        try {
            Order order = findOrder(orderCode);
            String token = tokenService.ensureToken(order);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderCode",    order.getOrderCode());
            m.put("invoiceToken", token);
            m.put("publicUrl",    tokenService.buildPublicUrl(token));
            m.put("qrImageUrl",   tokenService.buildQrImageUrl(token));
            return ResponseEntity.ok(ApiResponse.success(m, "OK"));
        } catch (Exception e) {
            log.error("[EInvoice][Sale] invoiceQr error orderCode={}", orderCode, e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────


    /**
     * Lỗi nghiệp vụ (dải chưa tạo, hết số, sai tài khoản...) chỉ log WARN 1 dòng —
     * không đổ stack trace vì đây không phải bug, và trả message tiếng Việt cho UI.
     */
    private <T> ResponseEntity<ApiResponse<T>> handleEInvoiceError(String op, String orderCode, Exception e) {
        if (e instanceof EInvoiceException ex) {
            log.warn("[EInvoice][Sale] {} orderCode={} — {} ({})", op, orderCode, ex.getMessage(), ex.getKind());
            return ResponseEntity.ok(ApiResponse.error(400, ex.getMessage()));
        }
        log.error("[EInvoice][Sale] {} error orderCode={}", op, orderCode, e);
        return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
    }

    private Order findOrder(String orderCode) {
        return orderRepo.findByOrderCode(orderCode)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderCode));
    }

    /** FE gửi {} khi không có thông tin người mua → coi như null */
    private BusinessBuyerInfo nullIfEmpty(BusinessBuyerInfo b) {
        if (b == null) return null;
        boolean empty = !notBlank(b.getTaxCode()) && !notBlank(b.getCompanyName())
                && !notBlank(b.getAddress()) && !notBlank(b.getEmail()) && !notBlank(b.getPhone());
        return empty ? null : b;
    }

    /** Format giống POS để frontend dùng chung component. */
    private Map<String, Object> toOrderMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            o.getId());
        m.put("orderCode",     o.getOrderCode());
        m.put("status",        o.getStatus());
        m.put("type",          o.getType());
        m.put("typeLabel",     typeLabel(o.getType()));
        m.put("customerType",  o.getCustomerType());

        m.put("customerName",  o.getCustomerName());
        m.put("customerPhone", o.getCustomerPhone());
        m.put("customerEmail", o.getCustomerEmail());
        m.put("paymentMethod", o.getPaymentMethod());
        m.put("paymentStatus", o.getPaymentStatus());

        // Đặt tên field trùng với POS để table/modal dùng lại được
        m.put("subtotal",       o.getSubtotal());
        m.put("totalAmount",    o.getTotalAmount());     // sau CK, chưa VAT
        m.put("totalVatAmount", o.getVatAmount());
        m.put("discountAmount", o.getDiscountAmount());
        m.put("discountRate",   o.getDiscountRate());
        m.put("finalAmount",    o.getFinalAmount());

        m.put("createdAt",      o.getCreatedAt());
        m.put("deliveryAddress", o.getDeliveryAddress());

        String invoiceEmail = notBlank(o.getInvoiceEmail()) ? o.getInvoiceEmail() : o.getCustomerEmail();

        boolean submitted = notBlank(o.getTaxCode()) || notBlank(invoiceEmail);
        m.put("invoiceSubmitted", submitted);
        m.put("invoiceEmail",     invoiceEmail);
        m.put("invoiceSubmittedAt", o.getInvoiceSubmittedAt());
        if (submitted) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("taxCode",        o.getTaxCode());
            d.put("companyName",    o.getCompanyName());
            d.put("email",          invoiceEmail);
            d.put("address",        o.getCompanyAddress());
            d.put("companyAddress", o.getCompanyAddress());
            d.put("invoiceEmail",   invoiceEmail);
            d.put("contactName",    o.getContactName());
            d.put("companyPhone",   o.getCompanyPhone());
            d.put("submittedAt",    o.getInvoiceSubmittedAt() != null
                    ? o.getInvoiceSubmittedAt() : o.getCreatedAt());
            m.put("invoiceDetail", d);
        }

        // QR để khách tự nhập thông tin xuất hóa đơn
        m.put("invoiceToken", o.getInvoiceToken());
        m.put("invoicePublicUrl", tokenService.buildPublicUrl(o.getInvoiceToken()));

        m.put("eInvoiceNo",         o.getEInvoiceNo());
        m.put("eInvoiceStatus",     o.getEInvoiceStatus());
        m.put("eInvoiceIssuedDate", o.getEInvoiceIssuedDate());
        m.put("eInvoiceTransactionId", o.getEInvoiceTransactionId());

        return m;
    }

    /** WHOLESALE → "Sỉ", RETAIL → "Lẻ". Giá trị lạ thì giữ nguyên. */
    static String typeLabel(String type) {
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
