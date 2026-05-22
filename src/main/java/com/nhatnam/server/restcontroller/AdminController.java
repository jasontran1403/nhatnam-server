package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.DashboardDto;
import com.nhatnam.server.dto.PosChartDto;
import com.nhatnam.server.dto.PosDashboardDto;
import com.nhatnam.server.dto.pos.*;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.pos.*;
import com.nhatnam.server.enumtype.PosCustomerType;
import com.nhatnam.server.enumtype.PosOrderStatus;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.pos.*;
import com.nhatnam.server.service.PosChartService;
import com.nhatnam.server.service.PosCustomerService;
import com.nhatnam.server.service.PosService;
import com.nhatnam.server.service.serviceimpl.DashboardService;
import com.nhatnam.server.utils.PosOrderExportService;
import com.nhatnam.server.utils.TelegramService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/admin")
public class AdminController {

    private final DashboardService dashboardService;
    private final PosUserStoreRepository posUserStoreRepository;
    private final PosCustomerRepository posCustomerRepo;
    private final PosCustomerService posCustomerService;
    private final PosStoreRepository        posStoreRepository;
    private final PosService posService;
    private final PosShiftRepository  posShiftRepository;

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @GetMapping("/dashboard/pos/export")
    public ResponseEntity<byte[]> exportPosOrders(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false) Long fromTs,
            @RequestParam(required = false) Long toTs,
            Authentication auth) {
        try {
            Long storeId   = extractStoreId(extractUserId(auth));
            String storeName = posStoreRepository.findById(storeId)
                    .map(PosStore::getName).orElse("Store");
            final long[] range = resolveTimeRange(period, fromTs, toTs);

            // ← Đồng bộ, trả file trực tiếp về client
            byte[] excel = posOrderExportService.exportForStore(
                    storeId, storeName, range[0], range[1]);

            String filename = "orders_" + storeId
                    + "_" + LocalDate.now(VN_ZONE) + ".xlsx";

            return ResponseEntity.ok()
                    .header("Content-Disposition",
                            "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type",
                            "application/vnd.openxmlformats-officedocument"
                                    + ".spreadsheetml.sheet")
                    .body(excel);

        } catch (Exception e) {
            log.error("[ADMIN] exportPosOrders error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/store/info")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStoreInfo(
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            PosStore store = posStoreRepository.findById(storeId)
                    .orElseThrow(() -> new RuntimeException("Store not found"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id",         store.getId());
            data.put("name",       store.getName());
            data.put("address",    store.getAddress());
            data.put("phone",      store.getPhone());
            data.put("avatarUrl",  store.getAvatarUrl());
            data.put("printerIp",  store.getPrinterIp() != null
                    ? store.getPrinterIp() : "");
            data.put("shopeeRate", store.getShopeeRate());
            data.put("grabRate",   store.getGrabRate());
            data.put("referralRate", store.getReferralRate());
            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PutMapping("/store/info")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateStoreInfo(
            @RequestBody Map<String, Object> req,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            PosStore store = posStoreRepository.findById(storeId)
                    .orElseThrow(() -> new RuntimeException("Store not found"));

            if (req.get("name") != null)
                store.setName((String) req.get("name"));
            if (req.get("address") != null)
                store.setAddress((String) req.get("address"));
            if (req.get("phone") != null)
                store.setPhone((String) req.get("phone"));
            if (req.get("printerIp") != null)
                store.setPrinterIp((String) req.get("printerIp"));
            if (req.get("shopeeRate") != null)
                store.setShopeeRate(new BigDecimal(req.get("shopeeRate").toString()));
            if (req.get("grabRate") != null)
                store.setGrabRate(new BigDecimal(req.get("grabRate").toString()));
            if (req.get("referralRate") != null)
                store.setReferralRate(new BigDecimal(req.get("referralRate").toString()));

            posStoreRepository.save(store);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id",         store.getId());
            data.put("name",       store.getName());
            data.put("address",    store.getAddress());
            data.put("phone",      store.getPhone());
            data.put("printerIp",  store.getPrinterIp());
            data.put("shopeeRate", store.getShopeeRate());
            data.put("grabRate",   store.getGrabRate());
            return ResponseEntity.ok(ApiResponse.success(data, "Đã cập nhật store"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // CATEGORY
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
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<PosCategoryResponse>> createCategory(
            @RequestBody CreatePosCategoryRequest req, Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            return ResponseEntity.ok(ApiResponse.success(
                    posService.createCategory(req, storeId), "Category created"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PutMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<PosCategoryResponse>> updateCategory(
            @PathVariable Long id,
            @RequestBody UpdatePosCategoryRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.updateCategory(id, req), "Category updated"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteCategory(
            @PathVariable Long id) {
        try {
            posService.deleteCategory(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Category deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // INGREDIENT
    // ════════════════════════════════════════

    @GetMapping("/ingredients")
    public ResponseEntity<ApiResponse<List<PosIngredientResponse>>> getIngredients(
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            return ResponseEntity.ok(ApiResponse.success(
                    posService.getAllIngredients(storeId), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/ingredients")
    public ResponseEntity<ApiResponse<PosIngredientResponse>> createIngredient(
            @Valid @RequestBody CreatePosIngredientRequest req,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            return ResponseEntity.ok(ApiResponse.success(
                    posService.createIngredient(req, storeId), "Ingredient created"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PutMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<PosIngredientResponse>> updateIngredient(
            @PathVariable Long id,
            @Valid @RequestBody CreatePosIngredientRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.updateIngredient(id, req), "Ingredient updated"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @DeleteMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteIngredient(
            @PathVariable Long id) {
        try {
            posService.deleteIngredient(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Ingredient deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // PRODUCT
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
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ApiResponse<PosProductResponse>> getProduct(
            @PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.getProductById(id), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @PostMapping("/products")
    public ResponseEntity<ApiResponse<PosProductResponse>> createProduct(
            @Valid @RequestBody CreatePosProductRequest req,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            return ResponseEntity.ok(ApiResponse.success(
                    posService.createProduct(req, storeId), "Product created"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PutMapping("/products/{id}")
    public ResponseEntity<ApiResponse<PosProductResponse>> updateProduct(
            @PathVariable Long id,
            @RequestBody UpdatePosProductRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.updateProduct(id, req), "Product updated"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteProduct(
            @PathVariable Long id) {
        try {
            posService.deleteProduct(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Product deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════
    // VARIANT
    // ════════════════════════════════════════

    @PostMapping("/variants")
    public ResponseEntity<ApiResponse<PosProductResponse>> createVariant(
            @Valid @RequestBody CreatePosVariantRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.createVariant(req), "Variant created"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @PutMapping("/variants/{id}")
    public ResponseEntity<ApiResponse<PosProductResponse>> updateVariant(
            @PathVariable Long id,
            @RequestBody CreatePosVariantRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.updateVariant(id, req), "Variant updated"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @DeleteMapping("/variants/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteVariant(
            @PathVariable Long id) {
        try {
            posService.deleteVariant(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Variant deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping("/pos-customers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPosCustomers(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth
    ) {
        try {
            User user    = (User) auth.getPrincipal();
            Long storeId = extractStoreId(user.getId());

            var stream = posCustomerRepo.findByStoreId(storeId)
                    .stream()
                    .sorted((a, b) -> Long.compare(
                            b.getCreatedAt() != null ? b.getCreatedAt() : 0,
                            a.getCreatedAt() != null ? a.getCreatedAt() : 0));

            if (search != null && !search.isBlank()) {
                final var q = search.toLowerCase();
                stream = stream.filter(c ->
                        c.getName().toLowerCase().contains(q) ||
                                c.getPhone().contains(q)
                );
            }

            var allList = stream.map(this::_toPosMap).toList();
            int total = allList.size();
            int start = page * size;
            int end   = Math.min(start + size, total);
            var content = start >= total ? List.of() : allList.subList(start, end);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",     content);
            result.put("totalItems",  total);
            result.put("currentPage", page);
            result.put("totalPages",  (int) Math.ceil((double) total / size));

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[ADMIN] getPosCustomers error", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, e.getMessage()));
        }
    }

    // ── Lấy chi tiết 1 POS customer ──────────────────────────────
    // GET /api/admin/pos-customers/{id}
    @GetMapping("/pos-customers/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPosCustomerById(
            @PathVariable Long id, Authentication auth) {
        try {
            User user    = (User) auth.getPrincipal();
            Long storeId = extractStoreId(user.getId());
            PosCustomer c = posCustomerRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy KH #" + id));
            if (!storeId.equals(c.getStoreId()))
                throw new RuntimeException("KH không thuộc store của bạn");
            return ResponseEntity.ok(ApiResponse.success(_toPosMap(c), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(404, e.getMessage()));
        }
    }

    // ── Tạo mới POS customer ──────────────────────────────────────
    // POST /api/admin/pos-customers
    @PostMapping("/pos-customers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPosCustomer(
            @RequestBody Map<String, String> req, Authentication auth) {
        try {
            User user    = (User) auth.getPrincipal();
            Long storeId = extractStoreId(user.getId());

            String phone = req.get("phone");
            String name  = req.get("name");
            if (phone == null || phone.isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu số điện thoại"));
            if (name == null || name.isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu tên"));

            PosCustomer c = posCustomerService.createOrUpdate(
                    phone, name, storeId,
                    req.get("dateOfBirth"),
                    req.get("deliveryAddress"),
                    req.get("referredByPhone"),
                    req.get("customerType"));   // ← THÊM

            return ResponseEntity.ok(ApiResponse.success(_toPosMap(c), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PutMapping("/pos-customers/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updatePosCustomer(
            @PathVariable Long id,
            @RequestBody Map<String, String> req,
            Authentication auth) {
        try {
            User user    = (User) auth.getPrincipal();
            Long storeId = extractStoreId(user.getId());
            PosCustomer c = posCustomerRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy KH #" + id));
            if (!storeId.equals(c.getStoreId()))
                throw new RuntimeException("KH không thuộc store của bạn");

            if (req.containsKey("name") && req.get("name") != null)
                c.setName(req.get("name").trim());
            if (req.containsKey("deliveryAddress"))
                c.setDeliveryAddress(req.get("deliveryAddress"));
            if (req.containsKey("dateOfBirth"))
                c.setDateOfBirth(req.get("dateOfBirth"));

            // ← THÊM: update customerType
            if (req.containsKey("customerType") && req.get("customerType") != null) {
                try {
                    c.setCustomerType(PosCustomerType.valueOf(
                            req.get("customerType").trim()));
                } catch (IllegalArgumentException ignored) {}
            }

            c = posCustomerRepo.save(c);
            return ResponseEntity.ok(ApiResponse.success(_toPosMap(c), "Cập nhật thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/customers/types")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getCustomerTypes() {
        var list = java.util.Arrays.stream(PosCustomerType.values())
                .map(t -> Map.of("value", t.name(), "label", t.getLabel()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    // ── Private helper ────────────────────────────────────────────
    private Long extractUserId(Authentication auth) {
        User user = (User) auth.getPrincipal();
        return user.getId();
    }

    private Long extractStoreId(Long userId) {
        return posUserStoreRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException(
                        "Tài khoản chưa được gán vào store nào."))
                .getStore().getId();
    }

    private Map<String, Object> _toPosMap(PosCustomer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                   c.getId());
        m.put("phone",                c.getPhone());
        m.put("name",                 c.getName());
        m.put("storeId",              c.getStoreId());
        m.put("totalSpend",           c.getTotalSpend());
        m.put("dateOfBirth",          c.getDateOfBirth());
        m.put("deliveryAddress",      c.getDeliveryAddress());
        m.put("referredByCustomerId", c.getReferredByCustomerId());
        m.put("referredByName",       c.getReferredByName());
        m.put("referredByPhone",      c.getReferredByPhone());
        m.put("createdAt",            c.getCreatedAt());
        // ← THÊM 2 dòng này
        m.put("customerType",      c.getCustomerType() != null
                ? c.getCustomerType().name() : "KLE");
        m.put("customerTypeLabel", c.getCustomerType() != null
                ? c.getCustomerType().getLabel() : "Khách lẻ");
        return m;
    }

    @GetMapping("/shifts")
    public ResponseEntity<ApiResponse<List<PosShiftResponse>>> getAllShifts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String search,
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            // Lấy tất cả shifts của store, sort theo openTime desc
            List<PosShift> shifts = posShiftRepository.findByStoreIdOrderByOpenTimeDesc(storeId);

            // Filter theo search (id hoặc staffName)
            if (search != null && !search.isBlank()) {
                final String s = search.toLowerCase();
                shifts = shifts.stream()
                        .filter(sh ->
                                String.valueOf(sh.getId()).contains(s) ||
                                        sh.getStaffName().toLowerCase().contains(s))
                        .toList();
            }

            List<PosShiftResponse> result = shifts.stream()
                    .map(posService::toShiftResponsePublic)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/shifts/{shiftId}/orders")
    public ResponseEntity<ApiResponse<List<PosOrderResponse>>> getOrdersByShift(
            @PathVariable Long shiftId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    posService.getOrdersByShift(shiftId), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/pos")
    public ResponseEntity<ApiResponse<PosDashboardDto.PosDashboard>> getPosDashboard(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false)        Long   fromTs,
            @RequestParam(required = false)        Long   toTs,
            @RequestParam(required = false)        String gran,
            Authentication authentication
    ) {
        try {
            User user = (User) authentication.getPrincipal();
            Long userId = user.getId();

            PosDashboardDto.DateRangeFilter filter      = buildPosFilter(period, fromTs, toTs);
            String granularity = gran != null ? gran : defaultGranularity(period, fromTs, toTs);


            var storeOptional = posUserStoreRepository.findByUserId(userId);

            if (storeOptional.isEmpty()) {
                return ResponseEntity.internalServerError()
                        .body(ApiResponse.error(922, "Tài khoản này chưa được gán xe"));
            }

            var store = storeOptional.get().getStore();

            PosDashboardDto.PosDashboard data =
                    dashboardService.getPosDashboard(filter, granularity, store.getId());

            return ResponseEntity.ok(ApiResponse.success(data, "POS Dashboard loaded"));

        } catch (Exception e) {
            log.error("POS Dashboard error", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(922, "Lỗi tải POS dashboard: " + e.getMessage()));
        }
    }

    private PosDashboardDto.DateRangeFilter buildPosFilter(String period, Long customFrom, Long customTo) {
        LocalDate today = LocalDate.now(VN_ZONE);
        return switch (period.toUpperCase()) {
            case "TODAY" -> {
                long from = today.atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new PosDashboardDto.DateRangeFilter(from, to);
            }
            case "7DAYS" -> {
                long from = today.minusDays(6).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new PosDashboardDto.DateRangeFilter(from, to);
            }
            case "30DAYS" -> {
                long from = today.minusDays(29).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new PosDashboardDto.DateRangeFilter(from, to);
            }
            case "MONTH" -> {
                long from = today.withDayOfMonth(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new PosDashboardDto.DateRangeFilter(from, to);
            }
            case "3MONTHS" -> {  // ← THÊM MỚI
                long from = today.minusMonths(3).withDayOfMonth(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new PosDashboardDto.DateRangeFilter(from, to);
            }
            case "6MONTHS" -> {  // ← THÊM MỚI
                long from = today.minusMonths(6).withDayOfMonth(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new PosDashboardDto.DateRangeFilter(from, to);
            }
            case "YEAR" -> {
                long from = today.minusMonths(11).withDayOfMonth(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new PosDashboardDto.DateRangeFilter(from, to);
            }
            case "CUSTOM" -> new PosDashboardDto.DateRangeFilter(customFrom, customTo);
            default       -> new PosDashboardDto.DateRangeFilter(null, null);
        };
    }

    private String defaultGranularity(String period, Long customFrom, Long customTo) {
        return switch (period.toUpperCase()) {
            case "TODAY"   -> "DAY";
            case "7DAYS"   -> "DAY";
            case "30DAYS",
                 "MONTH"   -> "DAY";
            case "3MONTHS",
                 "6MONTHS" -> "MONTH";  // ← THÊM MỚI
            case "YEAR"    -> "MONTH";
            case "CUSTOM"  -> {
                // Fix 4: Custom < 31 ngày → DAY, ngược lại → MONTH
                if (customFrom != null && customTo != null) {
                    long diffDays = (customTo - customFrom) / (1000L * 60 * 60 * 24);
                    yield diffDays <= 31 ? "DAY" : "MONTH";
                }
                yield "MONTH";
            }
            default -> "MONTH";
        };
    }

    private final PosOrderExportService posOrderExportService;

    // Helper: resolve time range từ period hoặc custom timestamps
    private long[] resolveTimeRange(String period, Long customFrom, Long customTo) {
        LocalDate today = LocalDate.now(VN_ZONE);
        return switch (period.toUpperCase()) {
            case "TODAY" -> new long[]{
                    today.atTime(0, 0, 1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23, 59, 59).atZone(VN_ZONE).toInstant().toEpochMilli()
            };
            case "7DAYS" -> new long[]{
                    today.minusDays(6).atTime(0, 0, 1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23, 59, 59).atZone(VN_ZONE).toInstant().toEpochMilli()
            };
            case "30DAYS" -> new long[]{
                    today.minusDays(29).atTime(0, 0, 1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23, 59, 59).atZone(VN_ZONE).toInstant().toEpochMilli()
            };
            case "3MONTHS" -> new long[]{
                    today.minusMonths(3).withDayOfMonth(1).atTime(0, 0, 1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23, 59, 59).atZone(VN_ZONE).toInstant().toEpochMilli()
            };
            case "6MONTHS" -> new long[]{
                    today.minusMonths(6).withDayOfMonth(1).atTime(0, 0, 1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23, 59, 59).atZone(VN_ZONE).toInstant().toEpochMilli()
            };
            case "YEAR" -> new long[]{
                    today.minusMonths(11).withDayOfMonth(1).atTime(0, 0, 1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23, 59, 59).atZone(VN_ZONE).toInstant().toEpochMilli()
            };
            case "CUSTOM" -> new long[]{
                    customFrom != null ? customFrom : today.atTime(0, 0, 1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    customTo   != null ? customTo   : today.atTime(23, 59, 59).atZone(VN_ZONE).toInstant().toEpochMilli()
            };
            default -> new long[]{
                    today.atTime(0, 0, 1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23, 59, 59).atZone(VN_ZONE).toInstant().toEpochMilli()
            };
        };
    }

    private final PosCustomerTypeRateRepository customerTypeRateRepo;

    @GetMapping("/store/type-rates")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTypeRates(
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            var list = customerTypeRateRepo.findByStoreId(storeId).stream()
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",        r.getId());
                        m.put("typeCode",  r.getTypeCode());
                        m.put("typeLabel", r.getTypeLabel());
                        m.put("accumRate", r.getAccumRate());
                        return m;
                    }).toList();
            return ResponseEntity.ok(ApiResponse.success(list, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/store/type-rates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTypeRate(
            @RequestBody Map<String, Object> req, Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            long now = System.currentTimeMillis();
            PosCustomerTypeRate rate = PosCustomerTypeRate.builder()
                    .storeId(storeId)
                    .typeCode(((String) req.get("typeCode")).trim().toUpperCase())
                    .typeLabel((String) req.get("typeLabel"))
                    .accumRate(new BigDecimal(req.get("accumRate").toString()))
                    .createdAt(now).updatedAt(now)
                    .build();
            rate = customerTypeRateRepo.save(rate);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rate.getId()); m.put("typeCode", rate.getTypeCode());
            m.put("typeLabel", rate.getTypeLabel()); m.put("accumRate", rate.getAccumRate());
            return ResponseEntity.ok(ApiResponse.success(m, "Đã tạo"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PutMapping("/store/type-rates/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTypeRate(
            @PathVariable Long id,
            @RequestBody Map<String, Object> req, Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            PosCustomerTypeRate rate = customerTypeRateRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy"));
            if (!rate.getStoreId().equals(storeId))
                throw new RuntimeException("Không thuộc store của bạn");
            if (req.get("typeLabel") != null) rate.setTypeLabel((String) req.get("typeLabel"));
            if (req.get("accumRate") != null)
                rate.setAccumRate(new BigDecimal(req.get("accumRate").toString()));
            rate.setUpdatedAt(System.currentTimeMillis());
            customerTypeRateRepo.save(rate);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rate.getId()); m.put("typeCode", rate.getTypeCode());
            m.put("typeLabel", rate.getTypeLabel()); m.put("accumRate", rate.getAccumRate());
            return ResponseEntity.ok(ApiResponse.success(m, "Đã cập nhật"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @DeleteMapping("/store/type-rates/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteTypeRate(
            @PathVariable Long id, Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            PosCustomerTypeRate rate = customerTypeRateRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy"));
            if (!rate.getStoreId().equals(storeId))
                throw new RuntimeException("Không thuộc store của bạn");
            customerTypeRateRepo.delete(rate);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    private final PosVoucherTemplateRepository voucherTemplateRepo;

    @GetMapping("/store/voucher-templates")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getVoucherTemplates(
            Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            var list = voucherTemplateRepo.findByStoreIdAndActiveTrue(storeId).stream()
                    .map(t -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",             t.getId());
                        m.put("name",           t.getName());
                        m.put("discountAmount", t.getDiscountAmount());
                        m.put("creditCost",     t.getCreditCost());
                        m.put("active",         t.isActive());
                        return m;
                    }).toList();
            return ResponseEntity.ok(ApiResponse.success(list, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/store/voucher-templates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createVoucherTemplate(
            @RequestBody Map<String, Object> req, Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            PosVoucherTemplate t = PosVoucherTemplate.builder()
                    .storeId(storeId)
                    .name((String) req.get("name"))
                    .voucherType(PosVoucherTemplate.VoucherType.FIXED_AMOUNT)
                    .discountAmount(new BigDecimal(req.get("discountAmount").toString()))
                    .creditCost(new BigDecimal(req.get("creditCost").toString()))
                    .active(true)
                    .build();
            t = voucherTemplateRepo.save(t);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId()); m.put("name", t.getName());
            m.put("discountAmount", t.getDiscountAmount());
            m.put("creditCost", t.getCreditCost()); m.put("active", t.isActive());
            return ResponseEntity.ok(ApiResponse.success(m, "Đã tạo"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PutMapping("/store/voucher-templates/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateVoucherTemplate(
            @PathVariable Long id,
            @RequestBody Map<String, Object> req, Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            PosVoucherTemplate t = voucherTemplateRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy"));
            if (!t.getStoreId().equals(storeId))
                throw new RuntimeException("Không thuộc store");
            if (req.get("name") != null) t.setName((String) req.get("name"));
            if (req.get("discountAmount") != null)
                t.setDiscountAmount(new BigDecimal(req.get("discountAmount").toString()));
            if (req.get("creditCost") != null)
                t.setCreditCost(new BigDecimal(req.get("creditCost").toString()));
            if (req.get("active") != null)
                t.setActive((Boolean) req.get("active"));
            voucherTemplateRepo.save(t);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId()); m.put("name", t.getName());
            m.put("discountAmount", t.getDiscountAmount());
            m.put("creditCost", t.getCreditCost()); m.put("active", t.isActive());
            return ResponseEntity.ok(ApiResponse.success(m, "Đã cập nhật"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @DeleteMapping("/store/voucher-templates/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteVoucherTemplate(
            @PathVariable Long id, Authentication auth) {
        try {
            Long storeId = extractStoreId(extractUserId(auth));
            PosVoucherTemplate t = voucherTemplateRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy"));
            if (!t.getStoreId().equals(storeId))
                throw new RuntimeException("Không thuộc store");
            t.setActive(false);
            voucherTemplateRepo.save(t);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    private final PosChartService posChartService;

    @GetMapping("/dashboard/charts/categories")
    public ResponseEntity<ApiResponse<List<PosChartDto.CategoryItem>>> getChartCategories(
            Authentication auth) {
        Long storeId = extractStoreId(extractUserId(auth));
        return ResponseEntity.ok(ApiResponse.success(
                posChartService.getCategories(storeId), "OK"));
    }

    // GET /api/admin/charts/monthly-shift
    @GetMapping("/dashboard/charts/period-shift")
    public ResponseEntity<ApiResponse<List<PosChartDto.PeriodShiftPoint>>> getPeriodShift(
            @RequestParam Long fromTs,
            @RequestParam Long toTs,
            @RequestParam(defaultValue = "MONTH_30") String periodUnit,
            @RequestParam(required = false) List<String> categories,
            Authentication auth) {
        try {
            Long storeId = posChartService.resolveStoreId(extractUserId(auth), null);
            var data = posChartService.getPeriodByShift(storeId, periodUnit, fromTs, toTs, categories);
            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getPeriodShift error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/charts/period-stacked")
    public ResponseEntity<ApiResponse<List<PosChartDto.PeriodStackedPoint>>> getPeriodStacked(
            @RequestParam Long fromTs,
            @RequestParam Long toTs,
            @RequestParam(defaultValue = "MONTH_30") String periodUnit,
            @RequestParam(required = false) List<String> categories,
            Authentication auth) {
        try {
            Long storeId = posChartService.resolveStoreId(extractUserId(auth), null);
            List<PosChartDto.PeriodStackedPoint> data =
                    posChartService.getPeriodStackedByShift(
                            storeId, periodUnit, fromTs, toTs, categories);
            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getPeriodStacked error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    // Thêm constant vào class PosChartService (nếu chưa có):
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @GetMapping("/dashboard/charts/heatmap")
    public ResponseEntity<ApiResponse<List<PosChartDto.HeatmapCell>>> getHeatmap(
            Authentication auth,
            @RequestParam(defaultValue = "60") int periodMinutes,
            @RequestParam(defaultValue = "0") long fromTs,
            @RequestParam(defaultValue = "0") long toTs,
            @RequestParam(required = false) List<Long> productIds) {
        try {
            if (periodMinutes != 30 && periodMinutes != 60 && periodMinutes != 120)
                periodMinutes = 60;
            if (fromTs == 0) {
                LocalDate today = LocalDate.now(VN);
                fromTs = today.minusMonths(1).withDayOfMonth(1)
                        .atStartOfDay(VN).toInstant().toEpochMilli();
                toTs = today.withDayOfMonth(today.getMonth().length(today.isLeapYear()))
                        .plusDays(1).atStartOfDay(VN).toInstant().toEpochMilli() - 1;
            }
            Long storeId = posChartService.resolveStoreId(extractUserId(auth), null);
            var result = posChartService.getHeatmap(storeId, periodMinutes, fromTs, toTs, productIds);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getHeatmap error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/charts/products")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getHeatmapProducts(
            Authentication auth) {
        try {
            Long storeId = posChartService.resolveStoreId(extractUserId(auth), null);
            List<Map<String, Object>> products = posChartService.getProductsForHeatmap(storeId);
            return ResponseEntity.ok(ApiResponse.success(products, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getHeatmapProducts error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }
}