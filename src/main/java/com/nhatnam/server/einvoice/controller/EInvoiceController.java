package com.nhatnam.server.einvoice.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.einvoice.EInvoiceException;
import com.nhatnam.server.einvoice.dto.EInvoiceRequestDto.*;
import com.nhatnam.server.einvoice.dto.ViettelInvoiceDto.BuyerInfo;
import com.nhatnam.server.einvoice.service.ViettelEInvoiceService;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.pos.PosOrder;
import com.nhatnam.server.repository.pos.PosOrderRepository;
import com.nhatnam.server.repository.pos.SellerStoreRepository;
import com.nhatnam.server.repository.pos.PosUserStoreRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Controller xử lý tạo hóa đơn điện tử Viettel cho đơn POS.
 * Base path: /api/pos/einvoice
 *
 * Tích hợp vào flow POS:
 *   Bước 2.1 → POST /api/pos/einvoice/retail/{orderId}
 *   Bước 2.2 → POST /api/pos/einvoice/business/{orderId}
 *
 * Lấy file:  GET  /api/pos/einvoice/{invoiceNo}/pdf
 */
@RestController
@RequestMapping("/api/pos/einvoice")
@RequiredArgsConstructor
@Log4j2
public class EInvoiceController {

    private final ViettelEInvoiceService einvoiceService;
    private final PosOrderRepository     posOrderRepo;
    private final SellerStoreRepository  sellerStoreRepository;
    private final PosUserStoreRepository posUserStoreRepository;
    private final com.nhatnam.server.repository.pos.PosStoreRepository posStoreRepository;
    private final com.nhatnam.server.repository.OrderRepository orderRepository;

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * GET /api/pos/einvoice/orders
     * Params:
     *   date=2025-05-20          → lấy 1 ngày (mặc định hôm nay)
     *   fromDate=...&toDate=...  → lấy theo range
     *   page, size
     *
     * ACCOUNTANT / SUPERADMIN → lấy tất cả PosOrder, không filter store.
     * Các role khác → filter theo store của user.
     */
    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listOrders(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) Long storeId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();

            // ── Tính khoảng thời gian (GMT+7) ──────────────────────
            LocalDate ldFrom, ldTo;
            if (fromDate != null && !fromDate.isBlank()
                    && toDate != null && !toDate.isBlank()) {
                ldFrom = LocalDate.parse(fromDate);
                ldTo   = LocalDate.parse(toDate);
            } else {
                ldFrom = ldTo = (date != null && !date.isBlank())
                        ? LocalDate.parse(date)
                        : LocalDate.now(VN_ZONE);
            }
            long fromTs = ldFrom.atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
            long toTs   = ldTo.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;

            var pr = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

            // ── Query theo role ─────────────────────────────────────
            org.springframework.data.domain.Page<PosOrder> paged;

            boolean isGlobal = user.getRole() == com.nhatnam.server.enumtype.Role.ACCOUNTANT
                    || user.getRole() == com.nhatnam.server.enumtype.Role.SUPERADMIN;

            if (isGlobal) {
                // ACCOUNTANT/SUPERADMIN được phép lọc theo cửa hàng bất kỳ (storeId=null → tất cả)
                paged = (storeId != null)
                        ? posOrderRepo.findByStoreIdAndTimeRange(storeId, fromTs, toTs, pr)
                        : posOrderRepo.findAllByTimeRange(fromTs, toTs, pr);
            } else {
                // Role khác luôn bị khóa vào store của chính mình, bỏ qua storeId client gửi lên
                Long ownStoreId = resolveStoreIdForSeller(user.getId());
                paged = posOrderRepo.findByStoreIdAndTimeRange(ownStoreId, fromTs, toTs, pr);
            }

            List<Map<String, Object>> rows = paged.getContent().stream()
                    .map(this::toOrderMap)
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",       rows);
            result.put("totalElements", paged.getTotalElements());
            result.put("totalPages",    paged.getTotalPages());
            result.put("currentPage",   paged.getNumber());

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[EInvoice] listOrders error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    /**
     * GET /api/pos/einvoice/stores
     * Danh sách cửa hàng để đổ dropdown filter ở màn hình hóa đơn.
     * ACCOUNTANT/SUPERADMIN → tất cả cửa hàng đang hoạt động.
     * Role khác → chỉ cửa hàng của chính mình.
     */
    @GetMapping("/stores")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listStores(Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            boolean isGlobal = user.getRole() == com.nhatnam.server.enumtype.Role.ACCOUNTANT
                    || user.getRole() == com.nhatnam.server.enumtype.Role.SUPERADMIN;

            var stores = posStoreRepository.findAllByOrderByIdAsc().stream()
                    .filter(s -> isGlobal || s.getId().equals(resolveStoreIdForSeller(user.getId())))
                    .filter(com.nhatnam.server.entity.pos.PosStore::isActive)
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<String, Object>();
                        m.put("id", s.getId());
                        m.put("name", s.getName());
                        return m;
                    })
                    .toList();

            return ResponseEntity.ok(ApiResponse.success(stores, "OK"));
        } catch (Exception e) {
            log.error("[EInvoice] listStores error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }


    /**
     * Lỗi nghiệp vụ (dải chưa tạo, hết số, sai tài khoản...) chỉ log WARN 1 dòng —
     * không đổ stack trace vì đây không phải bug, và trả message tiếng Việt cho UI.
     */
    private <T> ResponseEntity<ApiResponse<T>> handleEInvoiceError(String op, String orderCode, Exception e) {
        if (e instanceof EInvoiceException ex) {
            log.warn("[EInvoice] {} orderCode={} — {} ({})", op, orderCode, ex.getMessage(), ex.getKind());
            return ResponseEntity.ok(ApiResponse.error(400, ex.getMessage()));
        }
        log.error("[EInvoice] {} error orderCode={}", op, orderCode, e);
        return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
    }

    /** SELLER / ADMIN / POS — phải có store */
    private Long resolveStoreIdForSeller(Long userId) {
        var ss = sellerStoreRepository.findBySellerId(userId);
        if (ss.isPresent()) return ss.get().getStore().getId();
        return posUserStoreRepository.findByUserId(userId)
                .map(pus -> pus.getStore().getId())
                .orElseThrow(() -> new RuntimeException("Tài khoản chưa được gán store"));
    }

    private Map<String, Object> toOrderMap(PosOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                  o.getId());
        m.put("orderCode",           o.getOrderCode());
        m.put("appOrderCode",        o.getAppOrderCode());
        m.put("status",              o.getStatus());

        // Store info
        m.put("storeId",    o.getStore() != null ? o.getStore().getId()   : null);
        m.put("storeName",  o.getStore() != null ? o.getStore().getName() : null);

        // Khách hàng
        m.put("customerName",        o.getCustomerName());
        m.put("customerPhone",       o.getCustomerPhone());
        m.put("orderSource",         o.getOrderSource());
        m.put("paymentMethod",       o.getPaymentMethod());
        m.put("invoiceAddress", o.getInvoiceAddress());

        // Tiền
        m.put("totalAmount",         o.getTotalAmount());      // tạm tính (trước VAT, trước CK)
        m.put("totalVatAmount",      o.getTotalVatAmount());   // thuế VAT
        m.put("discountAmount",      o.getDiscountAmount());   // chiết khấu
        m.put("finalAmount",         o.getFinalAmount());      // cuối cùng

        m.put("createdAt",           o.getCreatedAt());

        // Invoice submitted info
        boolean submitted = o.getInvoiceTaxCode() != null && !o.getInvoiceTaxCode().isBlank();
        m.put("invoiceSubmitted",    submitted);
        if (submitted) {
            Map<String, Object> invoiceDetail = new LinkedHashMap<>();
            invoiceDetail.put("taxCode",     o.getInvoiceTaxCode());
            invoiceDetail.put("companyName", o.getInvoiceCompanyName());
            invoiceDetail.put("email",       o.getInvoiceEmail());
            invoiceDetail.put("submittedAt", o.getInvoiceSubmittedAt());
            invoiceDetail.put("address", o.getInvoiceAddress());
            m.put("invoiceDetail", invoiceDetail);
        }

        // EInvoice result
        m.put("eInvoiceNo",          o.getEInvoiceNo());
        m.put("eInvoiceStatus",      o.getEInvoiceStatus());
        m.put("eInvoicePdfUrl",      o.getEInvoicePdfUrl());
        m.put("eInvoiceIssuedDate",  o.getEInvoiceIssuedDate());

        // Deadline check (12h GMT+7)
        boolean expired = o.getCreatedAt() != null
                && (System.currentTimeMillis() - o.getCreatedAt()) > 12L * 3600_000L;
        m.put("invoiceDeadlineExpired", expired);

        return m;
    }

    /**
     * Lưu kết quả hóa đơn vào PosOrder sau khi tạo thành công.
     */
    private void saveInvoiceResult(PosOrder order, EInvoiceResult result, String status) {
        try {
            order.setEInvoiceNo(result.getInvoiceNo());
            order.setEInvoiceIssuedDate(result.getInvoiceIssuedDate());
            order.setEInvoiceStatus(status);
            // Lưu mẫu số/ký hiệu thực tế — cần khi lấy file PDF/XML về sau
            order.setEInvoiceTemplateCode(result.getTemplateCode());
            order.setEInvoiceSeries(result.getInvoiceSeries());
            // Lưu transactionID để dùng khi gửi CQT
            if (result.getTransactionID() != null) {
                order.setEInvoiceTransactionId(result.getTransactionID());
            }
            posOrderRepo.save(order);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(EInvoiceController.class)
                    .error("[EInvoice] Không lưu được kết quả vào PosOrder #{}: {}", order.getId(), e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Bước 2.1: Khách lẻ KHÔNG lấy hóa đơn
    // ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/pos/einvoice/retail/{orderId}
     *
     * Tạo hóa đơn điện tử cho khách lẻ (buyerNotGetInvoice=1).
     * Không cần body — chỉ cần orderId.
     */
    @PostMapping("/retail/{orderCode}")
    public ResponseEntity<ApiResponse<EInvoiceResult>> createRetailInvoice(
            @PathVariable String orderCode,
            Authentication auth) {
        try {
            PosOrder order = findOrder(orderCode, auth);
            EInvoiceResult result = einvoiceService.createRetailInvoice(order);

            if ("ERROR".equals(result.getStatus())) {
                return ResponseEntity.ok(
                        ApiResponse.error(400, "Tạo hóa đơn thất bại: " + result.getErrorMessage()));
            }

            log.info("[EInvoice] Retail invoice created: orderCode={}, invoiceNo={}",
                    orderCode, result.getInvoiceNo());
            saveInvoiceResult(order, result, "ISSUED");
            return ResponseEntity.ok(ApiResponse.success(result, "Tạo hóa đơn thành công"));

        } catch (Exception e) {
            return handleEInvoiceError("retail", orderCode, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Bước 2.2: Khách CÓ MST
    // ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/pos/einvoice/business/{orderId}
     *
     * Body:
     * {
     *   "taxCode":     "0100109106-999",
     *   "companyName": "Công ty TNHH ABC",
     *   "address":     "123 Nguyễn Huệ, Q1, TP.HCM",
     *   "email":       "ketoan@abc.vn",
     *   "phone":       "0901234567"
     * }
     */
    @PostMapping("/business/{orderCode}")
    public ResponseEntity<ApiResponse<EInvoiceResult>> createBusinessInvoice(
            @PathVariable String orderCode,
            @RequestBody BusinessBuyerInfo buyerInfo,
            Authentication auth) {
        try {
            if (buyerInfo.getTaxCode() == null || buyerInfo.getTaxCode().isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu mã số thuế người mua"));
            }
            if (buyerInfo.getCompanyName() == null || buyerInfo.getCompanyName().isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu tên công ty người mua"));
            }

            PosOrder order = findOrder(orderCode, auth);
            EInvoiceResult result = einvoiceService.createBusinessInvoice(order, buyerInfo);

            if ("ERROR".equals(result.getStatus())) {
                return ResponseEntity.ok(
                        ApiResponse.error(400, "Tạo hóa đơn thất bại: " + result.getErrorMessage()));
            }

            log.info("[EInvoice] Business invoice created: orderCode={}, invoiceNo={}, taxCode={}",
                    orderCode, result.getInvoiceNo(), buyerInfo.getTaxCode());
            saveInvoiceResult(order, result, "ISSUED");
            return ResponseEntity.ok(ApiResponse.success(result, "Tạo hóa đơn thành công"));

        } catch (Exception e) {
            return handleEInvoiceError("business", orderCode, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Tạo hóa đơn nháp (preview)
    // ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/pos/einvoice/draft/{orderId}
     *
     * Tạo nháp để nhân viên xem trước. Body giống /business hoặc để trống cho retail.
     * Dùng endpoint createOrUpdateInvoiceDraft.
     */
    @PostMapping("/draft/{orderCode}")
    public ResponseEntity<ApiResponse<EInvoiceResult>> createDraftInvoice(
            @PathVariable String orderCode,
            @RequestBody(required = false) BusinessBuyerInfo buyerInfo,
            Authentication auth) {
        try {
            PosOrder order = findOrder(orderCode, auth);

            BuyerInfo buyer;
            if (buyerInfo != null && buyerInfo.getTaxCode() != null) {
                // Có MST
                buyer = BuyerInfo.builder()
                        .buyerLegalName(buyerInfo.getCompanyName())
                        .buyerTaxCode(buyerInfo.getTaxCode())
                        .buyerAddressLine(buyerInfo.getAddress())
                        .buyerEmail(buyerInfo.getEmail())
                        .buyerPhoneNumber(buyerInfo.getPhone())
                        .buyerNotGetInvoice("0")
                        .build();
            } else {
                // Khách lẻ
                buyer = BuyerInfo.builder()
                        .buyerName("Người mua không lấy hóa đơn")
                        .buyerNotGetInvoice("1")
                        .build();
            }

            EInvoiceResult result = einvoiceService.createDraftInvoice(order, buyer);
            return ResponseEntity.ok(ApiResponse.success(result, "Tạo hóa đơn nháp thành công"));

        } catch (Exception e) {
            return handleEInvoiceError("draft", orderCode, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Lấy file hóa đơn
    // ─────────────────────────────────────────────────────────────────

    /**
     * GET /api/pos/einvoice/{invoiceNo}/pdf
     *
     * Trả về file PDF hóa đơn để download thẳng từ browser / POS app.
     * VD: GET /api/pos/einvoice/K24TXM5/pdf
     */
    @GetMapping("/{invoiceNo}/pdf")
    public ResponseEntity<byte[]> getInvoicePdf(@PathVariable String invoiceNo) {
        try {
            String base64 = einvoiceService.getInvoicePdfBase64(invoiceNo, resolveTemplateCode(invoiceNo));
            if (base64 == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] pdfBytes = einvoiceService.decodePdfBytes(base64);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"invoice-" + invoiceNo + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(pdfBytes.length)
                    .body(pdfBytes);

        } catch (EInvoiceException e) {
            log.warn("[EInvoice] getInvoicePdf invoiceNo={} — {}", invoiceNo, e.getMessage());
            return ResponseEntity.status(502).build();
        } catch (Exception e) {
            log.error("[EInvoice] getInvoicePdf error invoiceNo={}", invoiceNo, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/pos/einvoice/{invoiceNo}/pdf-base64
     *
     * Trả về Base64 PDF (dùng cho POS app mobile muốn render inline).
     */
    @GetMapping("/{invoiceNo}/pdf-base64")
    public ResponseEntity<ApiResponse<String>> getInvoicePdfBase64(@PathVariable String invoiceNo) {
        try {
            String base64 = einvoiceService.getInvoicePdfBase64(invoiceNo, resolveTemplateCode(invoiceNo));
            return ResponseEntity.ok(ApiResponse.success(base64, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    /**
     * GET /api/pos/einvoice/{invoiceNo}/xml
     *
     * Trả về file XML (dữ liệu gốc hóa đơn điện tử).
     */
    @GetMapping("/{invoiceNo}/xml")
    public ResponseEntity<byte[]> getInvoiceXml(@PathVariable String invoiceNo) {
        try {
            String base64 = einvoiceService.getInvoiceXmlBase64(invoiceNo, resolveTemplateCode(invoiceNo));
            byte[] xmlBytes = einvoiceService.decodePdfBytes(base64); // same decode logic

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"invoice-" + invoiceNo + ".xml\"")
                    .contentType(MediaType.APPLICATION_XML)
                    .body(xmlBytes);

        } catch (EInvoiceException e) {
            log.warn("[EInvoice] getInvoiceXml invoiceNo={} — {}", invoiceNo, e.getMessage());
            return ResponseEntity.status(502).build();
        } catch (Exception e) {
            log.error("[EInvoice] getInvoiceXml error invoiceNo={}", invoiceNo, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Xem trước PDF hóa đơn (không phát hành)
    // ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/pos/einvoice/preview/{orderCode}
     * Tạo PDF xem trước hóa đơn nháp (createInvoiceDraftPreview).
     * Không có giá trị pháp lý, không lưu DB.
     * Body (optional): BusinessBuyerInfo nếu là HĐ doanh nghiệp
     */
    @PostMapping("/preview/{orderCode}")
    public ResponseEntity<ApiResponse<String>> previewInvoice(
            @PathVariable String orderCode,
            @RequestBody(required = false) BusinessBuyerInfo buyerInfo,
            Authentication auth) {
        try {
            PosOrder order = findOrder(orderCode, auth);
            BuyerInfo buyer;
            if (buyerInfo != null && buyerInfo.getTaxCode() != null) {
                buyer = BuyerInfo.builder()
                        .buyerLegalName(buyerInfo.getCompanyName())
                        .buyerTaxCode(buyerInfo.getTaxCode())
                        .buyerAddressLine(
                                buyerInfo.getAddress() != null && !buyerInfo.getAddress().isBlank()
                                        ? buyerInfo.getAddress()
                                        : order.getInvoiceAddress()   // ← fallback từ DB
                        )
                        .buyerEmail(buyerInfo.getEmail())
                        .buyerPhoneNumber(buyerInfo.getPhone())
                        .buyerNotGetInvoice("0")
                        .build();
            } else {
                buyer = BuyerInfo.builder()
                        .buyerName("Người mua không lấy hóa đơn")
                        .buyerNotGetInvoice("1")
                        .build();
            }
            String pdfBase64 = einvoiceService.previewInvoice(order, buyer);
            return ResponseEntity.ok(ApiResponse.success(pdfBase64, "OK"));
        } catch (Exception e) {
            return handleEInvoiceError("preview", orderCode, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Gửi hóa đơn lên cơ quan thuế (CQT)
    // ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/pos/einvoice/{orderCode}/send-cqt
     * Gửi hóa đơn đã phát hành lên CQT.
     * Cần order đã có eInvoiceNo và transactionID.
     */
    @PostMapping("/{orderCode}/send-cqt")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendToCqt(
            @PathVariable String orderCode,
            Authentication auth) {
        try {
            PosOrder order = findOrder(orderCode, auth);
            if (order.getEInvoiceNo() == null || order.getEInvoiceNo().isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(400, "Đơn hàng chưa có hóa đơn được phát hành"));
            }
            Map<String, Object> result = einvoiceService.sendToCqt(
                    order.getEInvoiceNo(),
                    order.getEInvoiceTransactionId()
            );
            return ResponseEntity.ok(ApiResponse.success(result, "Gửi CQT thành công"));
        } catch (Exception e) {
            return handleEInvoiceError("sendToCqt", orderCode, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────

    /**
     * Hệ thống dùng 2 dải hóa đơn (POS máy tính tiền / bán sỉ/lẻ) nên phải tra ngược
     * mẫu số của chính hóa đơn đó trước khi xin file từ Viettel.
     * Không tìm thấy (hóa đơn cũ, phát hành trước khi có cột này) → null, service
     * sẽ fallback về mẫu số mặc định.
     */
    private String resolveTemplateCode(String invoiceNo) {
        var pos = posOrderRepo.findByEInvoiceNo(invoiceNo);
        if (pos.isPresent() && pos.get().getEInvoiceTemplateCode() != null) {
            return pos.get().getEInvoiceTemplateCode();
        }
        var sale = orderRepository.findByEInvoiceNo(invoiceNo);
        if (sale.isPresent() && sale.get().getEInvoiceTemplateCode() != null) {
            return sale.get().getEInvoiceTemplateCode();
        }
        return null;
    }

    private PosOrder findOrder(String orderCode, Authentication auth) {
        return posOrderRepo.findByOrderCode(orderCode)
                .or(() -> posOrderRepo.findByAppOrderCode(orderCode))
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderCode));
    }
}