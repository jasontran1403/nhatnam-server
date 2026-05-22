// src/main/java/com/nhatnam/server/restcontroller/PosController.java

package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.dto.pos.*;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.pos.*;
import com.nhatnam.server.enumtype.PosCustomerType;
import com.nhatnam.server.enumtype.ShiftStatus;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.pos.*;
import com.nhatnam.server.service.*;
import com.nhatnam.server.utils.PosReportAsyncService;
import com.nhatnam.server.utils.TelegramService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/pos")
public class PosController {
    private final PosService                    posService;
    private final PosExcelReportService         reportService;
    private final TelegramService               telegramService;
    private final PosShiftRepository            shiftRepo;
    private final PosUserStoreRepository        posUserStoreRepository;
    private final PosDiscountService            posDiscountService;
    private final PosDiscountProgramRepository  programRepo;    // ← THÊM
    private final PosCustomerService posCustomerService;
    private final PosCustomerRepository posCustomerRepo;
    private final ShiftImageService shiftImageService;

    private static final String DELETE_ORDER_PASSCODE = "160625";

    @GetMapping("/shifts/{shiftId}/opening-cash")
    public ResponseEntity<ApiResponse<BigDecimal>> getOpeningBalance(
            @PathVariable Long shiftId,
            Authentication auth) {
        try {
            Long userId = extractUserId(auth);
            Long storeId = extractStoreId(userId);

            // Kiểm tra shift thuộc store của user
            PosShift shift = shiftRepo.findById(shiftId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy ca #" + shiftId));

            if (!shift.getStoreId().equals(storeId)) {
                return ResponseEntity.ok(ApiResponse.error(403, "Không có quyền truy cập ca này"));
            }

            BigDecimal opening = posService.getOpeningBalance(shiftId);  // gọi service layer

            return ResponseEntity.ok(ApiResponse.success(opening, "OK"));
        } catch (RuntimeException e) {
            log.error("[POS] getOpeningBalance error for shift {}", shiftId, e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping(value = "/shifts/image/open", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ShiftOcrResponse>> uploadOpenShiftImage(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "shiftId", required = false) Long shiftId,
            Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            ShiftOcrResponse result = shiftImageService.processShiftImage(
                    file, "OPEN", shiftId, storeId);
            return ResponseEntity.ok(ApiResponse.success(result, "OCR hoàn thành"));
        } catch (Exception e) {
            log.error("[POS] uploadOpenShiftImage error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * Upload ảnh kiểm kho KHI ĐÓNG CA (đã có shift_id).
     * POST /api/pos/shifts/image/close
     * Form-data: file (image), shiftId (required)
     */
    @PostMapping(value = "/shifts/image/close", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ShiftOcrResponse>> uploadCloseShiftImage(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam("shiftId") Long shiftId,
            Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            ShiftOcrResponse result = shiftImageService.processShiftImage(
                    file, "CLOSE", shiftId, storeId);
            return ResponseEntity.ok(ApiResponse.success(result, "OCR hoàn thành"));
        } catch (Exception e) {
            log.error("[POS] uploadCloseShiftImage error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping(
            value    = "/shifts/images/open",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ApiResponse<ShiftOcrResponse>> uploadOpenShiftImages(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "shiftId", required = false) Long shiftId,
            Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            ShiftOcrResponse result =
                    shiftImageService.processShiftImages(files, "OPEN", shiftId, storeId);
            return ResponseEntity.ok(ApiResponse.success(result, "OCR hoàn thành"));
        } catch (Exception e) {
            log.error("[POS] uploadOpenShiftImages error", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── CLOSE — nhiều ảnh ────────────────────────────────────────────────────────
    @PostMapping(
            value    = "/shifts/images/close",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ApiResponse<ShiftOcrResponse>> uploadCloseShiftImages(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("shiftId") Long shiftId,
            Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            ShiftOcrResponse result =
                    shiftImageService.processShiftImages(files, "CLOSE", shiftId, storeId);
            return ResponseEntity.ok(ApiResponse.success(result, "OCR hoàn thành"));
        } catch (Exception e) {
            log.error("[POS] uploadCloseShiftImages error", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/customers/search")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchCustomers(
            @RequestParam String q,
            Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);

            // Normalize phone nếu query trông như SĐT
            String normalized = q.replaceAll("[\\s\\-]", "");
            if (normalized.startsWith("+84")) normalized = "0" + normalized.substring(3);
            else if (normalized.startsWith("84") && normalized.length() == 11)
                normalized = "0" + normalized.substring(2);

            List<Map<String, Object>> list = posCustomerRepo
                    .searchByStoreId(storeId, normalized)
                    .stream()
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .map(this::_toMap)
                    .toList();

            return ResponseEntity.ok(ApiResponse.success(list, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllCustomers(Authentication auth) {
        var userId  = extractUserId(auth);
        var storeId = extractStoreId(userId);

        var list = posCustomerRepo.findByStoreId(storeId)
                .stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::_toMap)
                .toList();

        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    private final PosCustomerOrderService customerOrderService;

    @GetMapping("/customers/{phone}/orders")
    public ResponseEntity<ApiResponse<PosCustomerOrderDto.PageResult>> getCustomerOrders(
            @PathVariable String phone,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            var result = customerOrderService.getOrdersByCustomer(
                    storeId, phone, page, size);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/customers/orders/{orderId}")
    public ResponseEntity<ApiResponse<PosCustomerOrderDto.OrderDetail>> getCustomerOrderDetail(
            @PathVariable Long orderId,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            var result = customerOrderService.getOrderDetail(storeId, orderId);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/customers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createOrUpdateCustomer(
            @RequestBody Map<String, String> req, Authentication auth) {
        try {
            String phone           = req.get("phone");
            String name            = req.get("name");
            String dateOfBirth     = req.get("dateOfBirth");     // nullable
            String deliveryAddress = req.get("deliveryAddress"); // nullable
            String referredByPhone = req.get("referredByPhone"); // nullable, chỉ set lần đầu
            String customerType = req.get("customerType"); // ← THÊM

            if (phone == null || phone.isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu số điện thoại"));
            if (name == null || name.isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu tên khách hàng"));

            var userId  = extractUserId(auth);
            var storeId = extractStoreId(userId);

            log.info(req.toString());

            PosCustomer c = posCustomerService.createOrUpdate(
                    phone, name, storeId, dateOfBirth, deliveryAddress, referredByPhone,  customerType);

            return ResponseEntity.ok(ApiResponse.success(_toMap(c), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    private Map<String, Object> _toMap(PosCustomer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                   c.getId());
        m.put("phone",                c.getPhone());
        m.put("name",                 c.getName());
        m.put("customerType", c.getCustomerType() != null
                ? c.getCustomerType().name() : "KLE");
        m.put("customerTypeLabel", c.getCustomerType() != null
                ? c.getCustomerType().getLabel() : "Khách lẻ");
        m.put("totalSpend",           c.getTotalSpend());
        m.put("dateOfBirth",          c.getDateOfBirth());
        m.put("deliveryAddress",      c.getDeliveryAddress());
        m.put("referredByCustomerId", c.getReferredByCustomerId());
        m.put("referredByName",       c.getReferredByName());
        m.put("referredByPhone",      c.getReferredByPhone());
        m.put("createdAt",            c.getCreatedAt());
        return m;
    }

    @GetMapping("/customers/types")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getCustomerTypes() {
        var list = java.util.Arrays.stream(PosCustomerType.values())
                .map(t -> Map.of("value", t.name(), "label", t.getLabel()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    @GetMapping("/discounts/customer/{customerId}/active")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getActiveDiscounts(
            @PathVariable Long customerId) {
        var list = posDiscountService.getActiveDiscounts(customerId)
                .stream().map(cd -> {
                    var p   = cd.getProgram();
                    var opt = cd.getSelectedOption();
                    java.math.BigDecimal max  = p.getMaxDiscountPerCustomer();
                    java.math.BigDecimal used = cd.getBudgetUsed();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",               cd.getId());
                    m.put("programId",        p.getId());
                    m.put("programName",      p.getName());
                    m.put("applyFrom",        fmtMs(p.getApplyFrom()));
                    m.put("applyTo",          fmtMs(p.getApplyTo()));
                    m.put("maxDiscount",      max);
                    m.put("budgetUsed",       used);
                    m.put("budgetRemaining",  max.subtract(used));
                    m.put("exhausted",        used.compareTo(max) >= 0);
                    m.put("selectedOptionId", opt != null ? opt.getId() : null);
                    m.put("options", p.getOptions().stream().map(o -> {
                        Map<String, Object> om = new LinkedHashMap<>();
                        om.put("id",            o.getId());
                        om.put("discountType",  o.getDiscountType().name());
                        om.put("discountValue", o.getDiscountValue());
                        om.put("maxPerUse",     o.getMaxPerUse());
                        om.put("label",         o.getLabel());
                        om.put("isItemType",    o.isItemType());
                        return om;
                    }).toList());
                    return m;
                }).toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    @PutMapping("/discounts/{customerDiscountId}/choose-option")
    public ResponseEntity<ApiResponse<String>> chooseOption(
            @PathVariable Long customerDiscountId,
            @RequestBody Map<String, Long> req) {
        posDiscountService.chooseOption(customerDiscountId, req.get("optionId"));
        return ResponseEntity.ok(ApiResponse.success("OK", "Đã chọn loại giảm giá"));
    }

    @GetMapping("/discounts/programs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPrograms() {
        var list = programRepo.findAllByOrderByCreatedAtDesc()
                .stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",                     p.getId());
                    m.put("name",                   p.getName());
                    m.put("status",                 p.getStatus().name());
                    m.put("qualifyFrom",            p.getQualifyFrom());
                    m.put("qualifyTo",              p.getQualifyTo());
                    m.put("applyFrom",              p.getApplyFrom());
                    m.put("applyTo",                p.getApplyTo());
                    m.put("minSpend",               p.getMinSpend());
                    m.put("maxDiscountPerCustomer", p.getMaxDiscountPerCustomer());
                    m.put("options", p.getOptions().stream().map(o -> {
                        Map<String, Object> om = new LinkedHashMap<>();
                        om.put("id",            o.getId());
                        om.put("discountType",  o.getDiscountType().name());
                        om.put("discountValue", o.getDiscountValue());
                        om.put("maxPerUse",     o.getMaxPerUse());
                        om.put("label",         o.getLabel());
                        return om;
                    }).toList());
                    return m;
                }).toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    @PostMapping("/discounts/programs")
    public ResponseEntity<ApiResponse<String>> createProgram(
            @RequestBody Map<String, Object> req) {
        try {
            PosDiscountProgram p = PosDiscountProgram.builder()
                    .name((String) req.get("name"))
                    .qualifyFrom(((Number) req.get("qualifyFrom")).longValue())
                    .qualifyTo  (((Number) req.get("qualifyTo")).longValue())
                    .applyFrom  (((Number) req.get("applyFrom")).longValue())
                    .applyTo    (((Number) req.get("applyTo")).longValue())
                    .minSpend(new java.math.BigDecimal(req.get("minSpend").toString()))
                    .maxDiscountPerCustomer(new java.math.BigDecimal(
                            req.get("maxDiscountPerCustomer").toString()))
                    .status(PosDiscountProgram.ProgramStatus.DRAFT)
                    .build();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> opts = (List<Map<String, Object>>) req.get("options");
            if (opts != null) {
                for (var opt : opts) {
                    p.getOptions().add(PosDiscountOption.builder()
                            .program(p)
                            .discountType(PosDiscountOption.DiscountType.valueOf(
                                    (String) opt.get("discountType")))
                            .discountValue(new java.math.BigDecimal(
                                    opt.get("discountValue").toString()))
                            .maxPerUse(opt.get("maxPerUse") != null
                                    ? new java.math.BigDecimal(opt.get("maxPerUse").toString())
                                    : null)
                            .label((String) opt.get("label"))
                            .build());
                }
            }
            programRepo.save(p);
            return ResponseEntity.ok(ApiResponse.success("OK", "Đã tạo chương trình"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PutMapping("/discounts/programs/{id}/activate")
    public ResponseEntity<ApiResponse<String>> activateProgram(@PathVariable Long id) {
        PosDiscountProgram p = programRepo.findById(id).orElseThrow();
        p.setStatus(PosDiscountProgram.ProgramStatus.ACTIVE);
        programRepo.save(p);
        int count = posDiscountService.qualifyCustomersForProgram(p, System.currentTimeMillis());
        return ResponseEntity.ok(ApiResponse.success("OK",
                "Đã kích hoạt. " + count + " khách đủ điều kiện."));
    }

    @PutMapping("/discounts/programs/{id}/end")
    public ResponseEntity<ApiResponse<String>> endProgram(
            @PathVariable Long id,
            @RequestBody Map<String, String> req) {
        posDiscountService.endProgram(id, req.get("reason"));
        return ResponseEntity.ok(ApiResponse.success("OK", "Đã kết thúc chương trình"));
    }

    // helper format ms → dd/MM/yyyy
    private static String fmtMs(Long ms) {
        if (ms == null) return "";
        return java.time.ZonedDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(ms),
                        java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    // ════════════════════════════════════════
    // STOCK IMPORT
    // ════════════════════════════════════════

    @GetMapping("/shifts/stock-import/history")
    public ResponseEntity<?> getStockImportHistory(Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            List<StockImportHistoryResponse> history = posService.getStockImportHistory(userId, storeId);
            return ResponseEntity.ok(Map.of("success", true, "data", history));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/shifts/stock-import")
    public ResponseEntity<?> importStock(
            @RequestBody StockImportRequest req, Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            List<StockImportResponse> result = posService.importStock(req, userId, storeId);
            return ResponseEntity.ok(Map.of("success", true, "message", "Nhập kho thành công.", "data", result));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // MENU PIN
    // ════════════════════════════════════════

    @PostMapping("/verify-menu-pin")
    public ResponseEntity<ApiResponse<Boolean>> verifyMenuPin(
            @RequestBody Map<String, String> req) {
        try {
            String pin = req.get("pin");
            if (pin == null || pin.length() != 6 || !pin.matches("\\d{6}")) {
                return ResponseEntity.badRequest().body(
                        ApiResponse.error(StatusCode.BAD_REQUEST, "Mật khẩu phải là 6 chữ số"));
            }
            boolean isValid = DELETE_ORDER_PASSCODE.equals(pin);
            if (isValid) {
                return ResponseEntity.ok(ApiResponse.success(true, "Xác thực thành công"));
            } else {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Mật khẩu không đúng"));
            }
        } catch (Exception e) {
            log.error("[POS] verify-menu-pin error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // REPORTS
    // ════════════════════════════════════════

    @PostMapping("/orders/{id}/delete")
    public ResponseEntity<?> deleteOrder(
            @PathVariable Long id,
            @RequestBody Map<String, String> req) {
        if (!DELETE_ORDER_PASSCODE.equals(req.get("passcode"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Mật khẩu không đúng"));
        }
        try {
            posService.deleteOrder(id);
            return ResponseEntity.ok(
                    Map.of("success", true, "message", "Đã xóa đơn hàng #" + id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }


    @GetMapping("/reports/shift/{shiftId}")
    public ResponseEntity<byte[]> exportShiftReportDirect(@PathVariable Long shiftId, Authentication auth) {
        try {
            PosShift shift = shiftRepo.findById(shiftId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy ca #" + shiftId));

            if (shift.getStatus() != ShiftStatus.CLOSED) {
                return ResponseEntity.badRequest().body("Ca chưa đóng".getBytes());
            }

            byte[] excel = reportService.generateShiftReport(shiftId);   // giả sử service này trả byte[]

            String fileName = "shift_report_" + shiftId + "_" + LocalDate.now() + ".xlsx";

            var userId = extractUserId(auth);

            var store = posUserStoreRepo.findByUserId(userId);

            if (store.isPresent() && store.get().getStore().getId() == 6) {
                telegramService.sendDocumentByGroupName("pos", excel,
                        "shift-report-" + shiftId + ".xlsx",
                        "📊 Báo cáo ca #" + shiftId + " - " + store.get().getStore().getName(), null);
            }

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(excel);
        } catch (Exception e) {
            log.error("[POS] exportShiftReportDirect error", e);
            return ResponseEntity.badRequest().body(("Lỗi: " + e.getMessage()).getBytes());
        }
    }

    // 2. Export theo khoảng ngày → trả file Excel trực tiếp
    @GetMapping("/reports/range")
    public ResponseEntity<byte[]> exportRangeReportDirect(
            @RequestParam String from,
            @RequestParam String to,
            Authentication auth) {

        try {
            Long userId = extractUserId(auth);

            byte[] excel = reportService.generateRangeReport(from, to, userId);

            String fileName = "report_" + from + "_" + to + ".xlsx";

            var store = posUserStoreRepo.findByUserId(userId);

            if (store.isPresent() && store.get().getId() == 6) {
                telegramService.sendDocumentByGroupName("pos", excel,
                        "shift-report-" + from + "_" + to + ".xlsx",
                        "📊 Báo cáo " + store.get().getStore().getName() + " các ca từ " + from + " đến " + to,
                        null);
            }

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(excel);

        } catch (Exception e) {
            log.error("[POS] exportRangeReportDirect error", e);
            return ResponseEntity.badRequest().body(("Lỗi: " + e.getMessage()).getBytes());
        }
    }

    // ════════════════════════════════════════
    // CATEGORY — data theo store
    // ════════════════════════════════════════
    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<PosCategoryResponse>>> getCategories(
            @RequestParam(defaultValue = "false") boolean includeDefault,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            return ResponseEntity.ok(ApiResponse.success(
                    posService.getAllCategories(storeId, includeDefault), "OK"));
        } catch (Exception e) {
            log.error("[POS] getCategories error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PutMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<PosCategoryResponse>> updateCategory(
            @PathVariable Long id, @RequestBody UpdatePosCategoryRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.updateCategory(id, req), "Category updated"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteCategory(@PathVariable Long id) {
        try {
            posService.deleteCategory(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Category deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @PostMapping("/categories/{categoryId}/products/{productId}")
    public ResponseEntity<ApiResponse<PosCategoryResponse>> addProductToCategory(
            @PathVariable Long categoryId, @PathVariable Long productId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.addProductToCategory(categoryId, productId), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // INGREDIENT — data theo store
    // ════════════════════════════════════════

    @GetMapping("/shifts/is-first-today")
    public ResponseEntity<ApiResponse<Boolean>> isFirstShiftToday(Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            boolean isFirst = posService.isFirstShiftOfDay(today, storeId);
            return ResponseEntity.ok(ApiResponse.success(isFirst, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/ingredients")
    public ResponseEntity<ApiResponse<List<PosIngredientResponse>>> getIngredients(Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            return ResponseEntity.ok(ApiResponse.success(
                    posService.getAllIngredients(storeId), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/ingredients")
    public ResponseEntity<ApiResponse<PosIngredientResponse>> createIngredient(
            @Valid @RequestBody CreatePosIngredientRequest req, Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            return ResponseEntity.ok(ApiResponse.success(
                    posService.createIngredient(req, storeId), "Ingredient created"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PutMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<PosIngredientResponse>> updateIngredient(
            @PathVariable Long id, @Valid @RequestBody CreatePosIngredientRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.updateIngredient(id, req), "Ingredient updated"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    private final PosUserStoreRepository posUserStoreRepo;

    @PatchMapping("/shifts/{shiftId}/open-inventory/{ingredientId}")
    public ResponseEntity<ApiResponse<PosShiftInventoryResponse>> updateOpenInventoryItem(
            @PathVariable Long shiftId,
            @PathVariable Long ingredientId,
            @RequestBody UpdateOpenInventoryRequest req,
            Authentication authentication
    ) {
        try {
            User user    = (User) authentication.getPrincipal();
            Long storeId = posUserStoreRepo.findByUserId(user.getId())
                    .orElseThrow(() -> new RuntimeException("Chưa gán store"))
                    .getStore().getId();

            PosShiftInventoryResponse result = posService.updateOpenInventoryItem(
                    shiftId, ingredientId,
                    req.getPackQuantity(),
                    req.getUnitQuantity() != null
                            ? req.getUnitQuantity()
                            : BigDecimal.ZERO,   // ← BigDecimal, fallback ZERO
                    storeId);

            return ResponseEntity.ok(ApiResponse.success(result, "Đã cập nhật kho đầu ca"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @DeleteMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteIngredient(@PathVariable Long id) {
        try {
            posService.deleteIngredient(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Ingredient deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // PRODUCT — data theo store
    // ════════════════════════════════════════

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<List<PosProductResponse>>> getProducts(
            @RequestParam(required = false) Long categoryId,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            List<PosProductResponse> products = categoryId != null
                    ? posService.getProductsByCategory(categoryId, storeId)
                    : posService.getAllActiveProducts(storeId);
            return ResponseEntity.ok(ApiResponse.success(products, "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ApiResponse<PosProductResponse>> getProduct(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(posService.getProductById(id), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @PostMapping("/products")
    public ResponseEntity<ApiResponse<PosProductResponse>> createProduct(
            @Valid @RequestBody CreatePosProductRequest req, Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            return ResponseEntity.ok(ApiResponse.success(
                    posService.createProduct(req, storeId), "Product created"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PutMapping("/products/{id}")
    public ResponseEntity<ApiResponse<PosProductResponse>> updateProduct(
            @PathVariable Long id, @RequestBody UpdatePosProductRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.updateProduct(id, req), "Product updated"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteProduct(@PathVariable Long id) {
        try {
            posService.deleteProduct(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Product deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // VARIANT
    // ════════════════════════════════════════

    @PostMapping("/variants")
    public ResponseEntity<ApiResponse<PosProductResponse>> createVariant(
            @Valid @RequestBody CreatePosVariantRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(posService.createVariant(req), "Variant created"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @PutMapping("/variants/{id}")
    public ResponseEntity<ApiResponse<PosProductResponse>> updateVariant(
            @PathVariable Long id, @RequestBody CreatePosVariantRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(posService.updateVariant(id, req), "Variant updated"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @DeleteMapping("/variants/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteVariant(@PathVariable Long id) {
        try {
            posService.deleteVariant(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Variant deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // APP MENU
    // ════════════════════════════════════════

    @PostMapping("/app-menus")
    public ResponseEntity<ApiResponse<PosProductResponse>> createAppMenu(
            @Valid @RequestBody CreatePosAppMenuRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(posService.createAppMenu(req), "App menu created"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @DeleteMapping("/app-menus/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteAppMenu(@PathVariable Long id) {
        try {
            posService.deleteAppMenu(id);
            return ResponseEntity.ok(ApiResponse.success(null, "App menu deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // SHIFT — theo store
    // ════════════════════════════════════════

    @PostMapping("/shifts/open")
    public ResponseEntity<ApiResponse<PosShiftResponse>> openShift(
            @Valid @RequestBody OpenShiftRequest req, Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            return ResponseEntity.ok(ApiResponse.success(
                    posService.openShift(req, userId, storeId), "Ca mở thành công"));
        } catch (RuntimeException e) {
            log.error("[POS] openShift error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PostMapping("/shifts/close")
    public ResponseEntity<ApiResponse<PosShiftResponse>> closeShift(
            @Valid @RequestBody CloseShiftRequest req, Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            return ResponseEntity.ok(ApiResponse.success(
                    posService.closeShift(req, userId, storeId), "Ca đóng thành công"));
        } catch (RuntimeException e) {
            log.error("[POS] closeShift error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @GetMapping("/shifts/current")
    public ResponseEntity<ApiResponse<PosShiftResponse>> getCurrentShift(Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            return ResponseEntity.ok(ApiResponse.success(
                    posService.getCurrentShift(userId, storeId), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping("/shifts/{id}")
    public ResponseEntity<ApiResponse<PosShiftResponse>> getShift(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(posService.getShiftById(id), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping("/shifts")
    public ResponseEntity<ApiResponse<List<PosShiftResponse>>> getShiftsByDate(
            @RequestParam String date, Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            return ResponseEntity.ok(ApiResponse.success(
                    posService.getShiftsByDate(date, storeId), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/shifts/{id}/report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getShiftReport(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(posService.getShiftReport(id), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    private final PosStoreRepository posStoreRepository;

    @GetMapping("/store/info")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStoreInfo(Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            PosStore store = posStoreRepository.findById(storeId)
                    .orElseThrow(() -> new RuntimeException("Store not found"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id",        store.getId());
            data.put("name",      store.getName());
            data.put("address",   store.getAddress());
            data.put("phone",     store.getPhone());
            data.put("avatarUrl", store.getAvatarUrl());
            data.put("printerIp", store.getPrinterIp() != null ? store.getPrinterIp() : "");
            data.put("shopeeRate", store.getShopeeRate());
            data.put("grabRate",   store.getGrabRate());
            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private final PosEVoucherRepository        eVoucherRepo;
    private final PosEVoucherUsageLogRepository eVoucherUsageLogRepo;

    @GetMapping("/accumulation/voucher-history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getVoucherHistory(
            @RequestParam Long customerId,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            var logs = eVoucherUsageLogRepo
                    .findByCustomerIdOrderByUsedAtDesc(customerId);

            var result = logs.stream()
                    .filter(l -> l.getStoreId().equals(storeId))
                    .map(l -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",                    l.getId());
                        m.put("voucherCode",           l.getVoucherCode());
                        m.put("voucherType",           l.getVoucherType());
                        m.put("voucherValue",          l.getVoucherValue());
                        m.put("actualDiscountApplied", l.getActualDiscountApplied());
                        m.put("orderAmountBefore",     l.getOrderAmountBeforeVoucher());
                        m.put("orderId",               l.getOrderId());
                        m.put("usedAt",                l.getUsedAt());
                        return m;
                    }).toList();

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // ORDER
    // ════════════════════════════════════════

    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<PosOrderResponse>> createOrder(
            @Valid @RequestBody CreatePosOrderRequest req, Authentication auth) {
        try {
            Long userId  = extractUserId(auth);
            Long storeId = extractStoreId(userId);
            PosOrderResponse order = posService.createOrder(req, userId, storeId);
            return ResponseEntity.ok(ApiResponse.success(order, "Đơn hàng tạo thành công"));
        } catch (IllegalArgumentException e) {
            log.warn("[POS] createOrder validation: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            log.error("[POS] createOrder error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<?> cancelOrder(
            @PathVariable Long id,
            @RequestBody CancelOrderRequest req,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            PosOrderResponse result = posService.cancelOrder(id, req.getPassword());
            return ResponseEntity.ok(Map.of("success", true, "message", "Đơn hàng đã hủy.", "data", result));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PutMapping("/orders/{id}/payment-method")
    public ResponseEntity<ApiResponse<PosOrderResponse>> updateOrderPaymentMethod(
            @PathVariable Long id,
            @RequestBody @Valid UpdatePaymentMethodRequest req,
            Authentication auth) {
        try {
            PosOrderResponse updated = posService.updateOrderPaymentMethod(id, req.getPaymentMethod());
            return ResponseEntity.ok(ApiResponse.success(updated, "Đã đổi phương thức thanh toán"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<ApiResponse<PosOrderResponse>> getOrder(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(posService.getOrderById(id), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping("/shifts/{shiftId}/orders")
    public ResponseEntity<ApiResponse<List<PosOrderResponse>>> getOrdersByShift(
            @PathVariable Long shiftId) {
        try {
            var results = posService.getOrdersByShift(shiftId);
            return ResponseEntity.ok(ApiResponse.success(results, "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════

    private Long extractUserId(Authentication auth) {
        User user = (User) auth.getPrincipal();
        return user.getId();
    }

    /**
     * Lấy storeId từ userId.
     * Throw RuntimeException nếu user chưa được gán store — 
     * client sẽ nhận được lỗi rõ ràng thay vì NullPointerException.
     */
    private Long extractStoreId(Long userId) {
        return posUserStoreRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException(
                        "Tài khoản chưa được gán vào store nào. Vui lòng liên hệ admin."))
                .getStore().getId();
    }

    // Thêm vào PosController.java

    private final PosAccumulationService  accumulationService;

    @GetMapping("/accumulation/credit-notes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getCreditNotes(
            @RequestParam Long customerId,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            var notes = accumulationService.getActiveCreditNotes(customerId, storeId);
            return ResponseEntity.ok(ApiResponse.success(
                    notes.stream().map(this::_toCreditNoteMap).toList(), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/accumulation/vouchers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getActiveVouchers(
            @RequestParam Long customerId,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            var vouchers = accumulationService.getActiveVouchers(customerId, storeId);
            return ResponseEntity.ok(ApiResponse.success(
                    vouchers.stream().map(this::_toVoucherMap).toList(), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/accumulation/voucher-templates")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getVoucherTemplates(
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            var templates = accumulationService.getTemplates(storeId);
            return ResponseEntity.ok(ApiResponse.success(
                    templates.stream().map(this::_toTemplateMap).toList(), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/accumulation/redeem")
    public ResponseEntity<ApiResponse<Map<String, Object>>> redeemCredit(
            @RequestBody Map<String, Long> req, Authentication auth) {
        try {
            Long storeId    = extractStoreId(extractUserId(auth));
            Long customerId = req.get("customerId");
            Long templateId = req.get("templateId");

            if (customerId == null || templateId == null)
                return ResponseEntity.ok(ApiResponse.error(400,
                        "Thiếu customerId / templateId"));

            PosEVoucher voucher = accumulationService.redeemCredit(
                    customerId, storeId, templateId);  // ← bỏ creditNoteId
            return ResponseEntity.ok(ApiResponse.success(
                    _toVoucherMap(voucher), "Đổi thành công"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/accumulation/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSummary(
            @RequestParam Long customerId,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            var record   = accumulationService.getCurrentMonthRecord(customerId, storeId);
            var credits  = accumulationService.getActiveCreditNotes(customerId, storeId);
            var vouchers = accumulationService.getActiveVouchers(customerId, storeId);

            BigDecimal totalCredit = credits.stream()
                    .map(PosCreditNote::getRemainingAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("currentMonthSpend",
                    record != null ? record.getTotalSpendNet() : BigDecimal.ZERO);
            m.put("currentMonthCredit",
                    record != null ? record.getCreditAmount() : BigDecimal.ZERO);
            m.put("totalAvailableCredit", totalCredit);
            m.put("activeCreditNoteCount", credits.size());
            m.put("activeVoucherCount",    vouchers.size());
            m.put("creditNotes",  credits.stream().map(this::_toCreditNoteMap).toList());
            m.put("vouchers",     vouchers.stream().map(this::_toVoucherMap).toList());
            return ResponseEntity.ok(ApiResponse.success(m, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

// ── Helper maps ───────────────────────────────────────────────────

    private Map<String, Object> _toCreditNoteMap(PosCreditNote n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              n.getId());
        m.put("sourceMonth",     n.getSourceMonth());
        m.put("type",            n.getType().name());
        m.put("amount",          n.getAmount());
        m.put("remainingAmount", n.getRemainingAmount());
        m.put("status",          n.getStatus().name());
        m.put("expiredAt",       n.getExpiredAt());
        m.put("createdAt",       n.getCreatedAt());
        return m;
    }

    private Map<String, Object> _toTemplateMap(PosVoucherTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              t.getId());
        m.put("name",            t.getName());
        m.put("voucherType",     t.getVoucherType().name());
        m.put("discountAmount",  t.getDiscountAmount());
        m.put("creditCost",      t.getCreditCost());
        return m;
    }

    private Map<String, Object> _toVoucherMap(PosEVoucher v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           v.getId());
        m.put("code",         v.getCode());
        m.put("voucherType",  v.getTemplate().getVoucherType().name());
        m.put("voucherValue", v.getVoucherValue());
        m.put("templateName", v.getTemplate().getName());
        m.put("creditUsed",   v.getCreditUsed());
        m.put("status",       v.getStatus().name());
        m.put("expiredAt",    v.getExpiredAt());
        m.put("createdAt",    v.getCreatedAt());
        return m;
    }
}