package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.DashboardDto;
import com.nhatnam.server.dto.RevenueIntelligenceDto;
import com.nhatnam.server.dto.request.CreateSellerRequest;
import com.nhatnam.server.dto.response.OrderResponse;
import com.nhatnam.server.entity.pos.PosOrder;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.pos.PosOrderRepository;
import com.nhatnam.server.service.*;
import jakarta.validation.Valid;
import com.nhatnam.server.dto.PosChartDto;
import com.nhatnam.server.dto.PosDashboardDto;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.Supplier;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.pos.PosCustomer;
import com.nhatnam.server.entity.pos.PosStore;
import com.nhatnam.server.enumtype.PosCustomerType;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.SupplierRepository;
import com.nhatnam.server.repository.pos.PosCustomerRepository;
import com.nhatnam.server.repository.pos.PosStoreRepository;
import com.nhatnam.server.repository.pos.PosUserStoreRepository;
import com.nhatnam.server.service.serviceimpl.DashboardService;
import com.nhatnam.server.utils.IngredientReportExport;
import com.nhatnam.server.utils.PosOrderExportService;
import com.nhatnam.server.utils.SellerOrderExportService;
import com.nhatnam.server.utils.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/superadmin")
public class SuperAdminController {

    private final RevenueIntelligenceService revenueIntelligenceService;
    private final DashboardService    dashboardService;
    private final PosStoreRepository  posStoreRepository;
    private final PosCustomerRepository posCustomerRepo;
    private final PosCustomerService posCustomerService;
    private final CustomerRepository customerRepository;
    private final IngredientReportExport ingredientReportExport;

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SupplierRepository supplierRepository;
    private final SellerStoreService sellerStoreService;
    private final com.nhatnam.server.repository.UserRepository userRepository;

    @GetMapping("/dashboard/pos/revenue-intelligence")
    public ResponseEntity<ApiResponse<RevenueIntelligenceDto.Response>> getRevenueIntelligence(
            @RequestParam Long storeId,
            @RequestParam long fromTs,
            @RequestParam long toTs,
            @RequestParam(defaultValue = "7")    int    shortWindow,
            @RequestParam(defaultValue = "28")   int    longWindow,
            @RequestParam(defaultValue = "0.5")  double alpha,
            @RequestParam(defaultValue = "0.10") double normalThreshold,
            @RequestParam(defaultValue = "0.20") double alertThreshold
    ) {
        try {
            RevenueIntelligenceDto.Config config = RevenueIntelligenceDto.Config.builder()
                    .shortWindow(shortWindow)
                    .longWindow(longWindow)
                    .alpha(alpha)
                    .normalThreshold(normalThreshold)
                    .alertThreshold(alertThreshold)
                    .build();

            RevenueIntelligenceDto.Response data =
                    revenueIntelligenceService.compute(storeId, fromTs, toTs, config);

            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            log.error("[RI] getRevenueIntelligence error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    // ── SELLER management ─────────────────────────────────────────

    @PostMapping("/sellers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSeller(
            @Valid @RequestBody CreateSellerRequest req) {
        try {
            Map<String, Object> result = sellerStoreService.createSellerWithStore(req);
            return ResponseEntity.ok(ApiResponse.success(result, "Tạo tài khoản SELLER thành công"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[SUPERADMIN] createSeller error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/sellers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSellers() {
        try {
            List<User> sellers = userRepository.findAll().stream()
                    .filter(u -> u.getRole() == com.nhatnam.server.enumtype.Role.SELLER)
                    .toList();

            List<Map<String, Object>> result = sellers.stream().map(seller -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",          seller.getId());
                m.put("username",    seller.getUsername());
                m.put("email",       seller.getEmail());
                m.put("fullName",    seller.getFullName());
                m.put("phoneNumber", seller.getPhoneNumber());
                m.put("isLocked",    seller.isLockAccount());
                m.put("timeCreate",  seller.getTimeCreate());

                boolean isLegacy = sellerStoreService.isLegacySeller(seller.getId());
                m.put("isLegacy", isLegacy);

                try {
                    Long storeId = sellerStoreService.resolveStoreIdForSeller(seller.getId());
                    posStoreRepository.findById(storeId).ifPresent(store -> {
                        m.put("storeId",   store.getId());
                        m.put("storeName", store.getName());
                    });
                } catch (Exception ignore) {
                    m.put("storeId",   null);
                    m.put("storeName", "Chưa gán store");
                }

                return m;
            }).toList();

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[SUPERADMIN] listSellers error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/suppliers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSuppliers() {
        try {
            List<Map<String, Object>> list = supplierRepository
                    .findByIsActiveTrueOrderByNameAsc()
                    .stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",      s.getId());
                        m.put("name",    s.getName());
                        m.put("address", s.getAddress());
                        m.put("phone",   s.getPhone());
                        return m;
                    })
                    .toList();
            return ResponseEntity.ok(ApiResponse.success(list, "OK"));
        } catch (Exception e) {
            log.error("[SELLER] getSuppliers error", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/suppliers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSupplier(
            @RequestBody Map<String, Object> req) {
        try {
            String name = req.get("name") instanceof String s ? s.trim() : null;
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("Tên nhà cung cấp không được để trống");

            Supplier supplier = Supplier.builder()
                    .name(name)
                    .address(req.get("address") instanceof String a ? a.trim() : null)
                    .phone(req.get("phone")   instanceof String p ? p.trim() : null)
                    .isActive(true)
                    .createdAt(System.currentTimeMillis())
                    .build();

            supplier = supplierRepository.save(supplier);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",      supplier.getId());
            m.put("name",    supplier.getName());
            m.put("address", supplier.getAddress());
            m.put("phone",   supplier.getPhone());

            return ResponseEntity.ok(ApiResponse.success(m, "Tạo nhà cung cấp thành công"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] createSupplier error", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/restaurant/export-ingredients")
    public ResponseEntity<ApiResponse<String>> exportRestaurantIngredients(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false) Long   fromTs,
            @RequestParam(required = false) Long   toTs,
            @RequestParam(required = false) String mode,
            Authentication auth
    ) {
        final long[] range = resolveTimeRange(period, fromTs, toTs);
        User actor = (User) auth.getPrincipal();

        CompletableFuture.runAsync(() -> {
            try {
                log.info("[INGREDIENT-EXPORT] period={} | mode={} | from={} ({}) | to={} ({})",
                        period, mode,
                        range[0], Instant.ofEpochMilli(range[0]).atZone(VN_ZONE).toLocalDateTime(),
                        range[1], Instant.ofEpochMilli(range[1]).atZone(VN_ZONE).toLocalDateTime());

                byte[] excel = ingredientReportExport.export(range[0], range[1]);

                String filename = "bao_cao_kho_"
                        + LocalDate.now(VN_ZONE) + ".xlsx";

                String fromStr = Instant.ofEpochMilli(range[0])
                        .atZone(VN_ZONE).toLocalDate()
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                String toStr = Instant.ofEpochMilli(range[1])
                        .atZone(VN_ZONE).toLocalDate()
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));

                String caption = "📦 Báo cáo Xuất/Nhập/Tồn"
                        + "\n📅 Từ: " + fromStr + " → " + toStr;

                telegramService.sendDocumentByGroupName(
                        "seller", excel, filename, caption, null);

            } catch (Exception e) {
                log.error("[INGREDIENT-EXPORT] async error", e);
            }
        });

        return ResponseEntity.ok(ApiResponse.success(
                "Đang tạo báo cáo nguyên liệu...",
                "Báo cáo sẽ được gửi vào Telegram"));
    }

    @GetMapping("/pos-customers/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPosCustomerById(
            @PathVariable Long id) {
        try {
            PosCustomer c = posCustomerRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy #" + id));
            return ResponseEntity.ok(ApiResponse.success(_toPosMap(c), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        }
    }

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
                    req.get("customerType"));

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

    private final PosUserStoreRepository posUserStoreRepository;
    private Long extractStoreId(Long userId) {
        return posUserStoreRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException(
                        "Tài khoản chưa được gán vào store nào. Vui lòng liên hệ admin."))
                .getStore().getId();
    }

    @GetMapping("/customers/types")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getCustomerTypes() {
        var list = java.util.Arrays.stream(PosCustomerType.values())
                .map(t -> Map.of("value", t.name(), "label", t.getLabel()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    @GetMapping("/b2b-customers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getB2bCustomers(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        try {
            var stream = customerRepository
                    .findByIsActiveTrueOrderByCustomerCodeAscNameAsc()
                    .stream();

            if (type != null && !type.isBlank()) {
                final var t = Customer.CustomerType.valueOf(type.toUpperCase());
                stream = stream.filter(c -> c.getCustomerType() == t);
            }
            if (search != null && !search.isBlank()) {
                final var q = search.toLowerCase();
                stream = stream.filter(c ->
                        (c.getCustomerCode() != null && c.getCustomerCode().toLowerCase().contains(q)) ||
                                (c.getShortName()    != null && c.getShortName().toLowerCase().contains(q))    ||
                                (c.getCompanyName()  != null && c.getCompanyName().toLowerCase().contains(q))  ||
                                (c.getPhone()        != null && c.getPhone().contains(q))                      ||
                                (c.getName()         != null && c.getName().toLowerCase().contains(q))
                );
            }

            var allList = stream.map(this::_toB2bMap).toList();
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
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/b2b-customers/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getB2bCustomerById(
            @PathVariable Long id) {
        try {
            Customer c = customerRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy #" + id));
            return ResponseEntity.ok(ApiResponse.success(_toB2bMap(c), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        }
    }

    @PostMapping("/b2b-customers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createB2bCustomer(
            @RequestBody Map<String, Object> req) {
        try {
            String code = req.get("customerCode") != null
                    ? ((String) req.get("customerCode")).trim().toUpperCase() : null;
            if (code == null || code.isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu customerCode"));
            if (customerRepository.findByCustomerCode(code).isPresent())
                return ResponseEntity.ok(ApiResponse.error(400, "Mã KH đã tồn tại: " + code));

            Customer c = new Customer();
            c.setCustomerCode(code);
            _applyB2bFields(req, c, true);
            c = customerRepository.save(c);
            return ResponseEntity.ok(ApiResponse.success(_toB2bMap(c), "Tạo thành công"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PutMapping("/b2b-customers/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateB2bCustomer(
            @PathVariable Long id,
            @RequestBody Map<String, Object> req) {
        try {
            Customer c = customerRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy #" + id));
            _applyB2bFields(req, c, false);
            c = customerRepository.save(c);
            return ResponseEntity.ok(ApiResponse.success(_toB2bMap(c), "Cập nhật thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    // ── Private helpers ───────────────────────────────────────────

    private void _applyB2bFields(Map<String, Object> req, Customer c, boolean isCreate) {
        if (isCreate) {
            String typeStr = (String) req.getOrDefault("customerType", "RETAIL");
            c.setCustomerType(Customer.CustomerType.valueOf(typeStr.toUpperCase()));
            c.setIsActive(true);
        }
        _strSet(req, "companyName",     c::setCompanyName);
        _strSet(req, "shortName",       c::setShortName);
        _strSet(req, "taxCode",         c::setTaxCode);
        _strSet(req, "address",         c::setAddress);
        _strSet(req, "deliveryAddress", c::setDeliveryAddress);
        _strSet(req, "contactName",     c::setContactName);
        _strSet(req, "dateOfBirth",     c::setDateOfBirth);
        _strSet(req, "phone",           c::setPhone);
        _strSet(req, "name",            c::setName);
        _strSet(req, "email",           c::setEmail);
        if (req.containsKey("discountRate") && req.get("discountRate") != null)
            c.setDiscountRate(((Number) req.get("discountRate")).intValue());
    }

    private void _strSet(Map<String, Object> req, String key,
                         java.util.function.Consumer<String> setter) {
        if (req.containsKey(key) && req.get(key) != null)
            setter.accept(((String) req.get(key)).trim());
    }

    private Map<String, Object> _toPosMap(PosCustomer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                   c.getId());
        m.put("phone",                c.getPhone());
        m.put("name",                 c.getName());
        m.put("storeId",              c.getStoreId());
        m.put("totalSpend",           c.getTotalSpend());
        m.put("storeName",            posStoreRepository.findById(c.getStoreId())
                .map(PosStore::getName).orElse("Store #" + c.getStoreId()));
        m.put("dateOfBirth",          c.getDateOfBirth());
        m.put("deliveryAddress",      c.getDeliveryAddress());
        m.put("referredByCustomerId", c.getReferredByCustomerId());
        m.put("referredByName",       c.getReferredByName());
        m.put("referredByPhone",      c.getReferredByPhone());
        m.put("createdAt",            c.getCreatedAt());
        m.put("customerType",      c.getCustomerType() != null
                ? c.getCustomerType().name() : "KLE");
        m.put("customerTypeLabel", c.getCustomerType() != null
                ? c.getCustomerType().getLabel() : "Khách lẻ");
        return m;
    }


    private Map<String, Object> _toB2bMap(Customer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              c.getId());
        m.put("customerCode",    c.getCustomerCode());
        m.put("customerType",    c.getCustomerType() != null
                ? c.getCustomerType().name() : "RETAIL");
        m.put("companyName",     c.getCompanyName());
        m.put("shortName",       c.getShortName());
        m.put("taxCode",         c.getTaxCode());
        m.put("address",         c.getAddress());
        m.put("deliveryAddress", c.getDeliveryAddress());
        m.put("contactName",     c.getContactName());
        m.put("dateOfBirth",     c.getDateOfBirth());
        m.put("phone",           c.getPhone());
        m.put("name",            c.getName());
        m.put("email",           c.getEmail());
        m.put("discountRate",    c.getDiscountRate());
        m.put("isActive",        c.getIsActive());
        m.put("createdAt",       c.getCreatedAt());
        m.put("companyPhone",   c.getCompanyPhone());
        m.put("companyAddress", c.getCompanyAddress());
        return m;
    }

    // ══════════════════════════════════════════════════════════════
    // POS STORES
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/pos-customers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllPosCustomers(
            @RequestParam(required = false) Long storeId
    ) {
        try {
            var stream = (storeId != null
                    ? posCustomerRepo.findByStoreId(storeId)
                    : posCustomerRepo.findAll())
                    .stream()
                    .sorted((a, b) -> Long.compare(
                            b.getCreatedAt() != null ? b.getCreatedAt() : 0,
                            a.getCreatedAt() != null ? a.getCreatedAt() : 0));

            var list = stream.map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",                   c.getId());
                m.put("phone",                c.getPhone());
                m.put("name",                 c.getName());
                m.put("storeId",              c.getStoreId());
                m.put("totalSpend",           c.getTotalSpend());
                m.put("dateOfBirth",          c.getDateOfBirth());
                m.put("deliveryAddress",      c.getDeliveryAddress());
                m.put("storeName",            posStoreRepository.findById(c.getStoreId())
                        .map(PosStore::getName).orElse("Store #" + c.getStoreId()));
                m.put("referredByCustomerId", c.getReferredByCustomerId());
                m.put("referredByName",       c.getReferredByName());
                m.put("referredByPhone",      c.getReferredByPhone());
                m.put("createdAt",            c.getCreatedAt());
                m.put("customerType",      c.getCustomerType() != null
                        ? c.getCustomerType().name() : "KLE");
                m.put("customerTypeLabel", c.getCustomerType() != null
                        ? c.getCustomerType().getLabel() : "Khách lẻ");
                return m;
            }).toList();

            return ResponseEntity.ok(ApiResponse.success(list, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/pos/vehicles")
    public ResponseEntity<ApiResponse<List<PosStoreDto>>> getPosVehicles() {
        List<PosStoreDto> list = posStoreRepository
                .findAllByOrderByIdAsc()
                .stream()
                .map(PosStoreDto::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    // ══════════════════════════════════════════════════════════════
    // RESTAURANT DASHBOARD
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/dashboard/restaurant")
    public ResponseEntity<ApiResponse<DashboardDto.RestaurantDashboard>> getRestaurantDashboard(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false) Long   fromTs,
            @RequestParam(required = false) Long   toTs,
            @RequestParam(required = false) String gran,
            @RequestParam(required = false) String mode
    ) {
        try {
            DashboardDto.DateRangeFilter filter = buildFilter(period, fromTs, toTs);
            String granularity = gran != null ? gran : defaultGranularity(period, fromTs, toTs);

            DashboardDto.RestaurantDashboard data =
                    dashboardService.getRestaurantDashboard(filter, granularity, mode);

            return ResponseEntity.ok(ApiResponse.success(data, "Dashboard loaded"));
        } catch (Exception e) {
            log.error("Dashboard error", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(921, "Lỗi tải dashboard: " + e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // POS DASHBOARD
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/dashboard/pos/chart")
    public ResponseEntity<ApiResponse<List<PosDashboardDto.PosOrderByTime>>> getPosChart(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false)        Long   fromTs,
            @RequestParam(required = false)        Long   toTs,
            @RequestParam(required = false)        String gran,
            @RequestParam(required = false)        Long   vehicleId,
            @RequestParam(required = false)        Long   storeId,
            @RequestParam(required = false)        String filterType
    ) {
        try {
            PosDashboardDto.DateRangeFilter filter =
                    buildPosFilter(period, fromTs, toTs);
            String granularity =
                    gran != null ? gran : defaultGranularity(period, fromTs, toTs);
            Long resolvedStoreId = storeId != null ? storeId : vehicleId;

            List<PosDashboardDto.PosOrderByTime> data =
                    dashboardService.getPosOrdersByTimePublic(
                            filter, granularity, resolvedStoreId, filterType);

            return ResponseEntity.ok(ApiResponse.success(data, "OK"));

        } catch (Exception e) {
            log.error("POS Chart error", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(923, "Lỗi tải chart: " + e.getMessage()));
        }
    }

    @GetMapping("/dashboard/pos")
    public ResponseEntity<ApiResponse<PosDashboardDto.PosDashboard>> getPosDashboard(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false)        Long   fromTs,
            @RequestParam(required = false)        Long   toTs,
            @RequestParam(required = false)        String gran,
            @RequestParam(required = false)        Long   vehicleId,
            @RequestParam(required = false)        Long   storeId,
            @RequestParam(required = false)        String filterType
    ) {
        try {
            PosDashboardDto.DateRangeFilter filter = buildPosFilter(period, fromTs, toTs);
            String granularity = gran != null ? gran : defaultGranularity(period, fromTs, toTs);
            Long resolvedStoreId = storeId != null ? storeId : vehicleId;

            PosDashboardDto.PosDashboard data =
                    dashboardService.getPosDashboard(filter, granularity, resolvedStoreId, filterType);

            return ResponseEntity.ok(ApiResponse.success(data, "POS Dashboard loaded"));

        } catch (Exception e) {
            log.error("POS Dashboard error", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(922, "Lỗi tải POS dashboard: " + e.getMessage()));
        }
    }

    @GetMapping("/dashboard/pos/rolling")
    public ResponseEntity<ApiResponse<PosDashboardDto.RollingPerformance>> getPosRolling(
            @RequestParam(defaultValue = "7") int    window,
            @RequestParam(required = false)  Long   anchorTs,
            @RequestParam(required = false)  Long   vehicleId,
            @RequestParam(required = false)  Long   storeId,
            @RequestParam(required = false)  String filterType
    ) {
        try {
            Long resolvedStoreId = storeId != null ? storeId : vehicleId;
            PosDashboardDto.RollingPerformance data =
                    dashboardService.getPosRolling(window, anchorTs, resolvedStoreId, filterType);
            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            log.error("POS Rolling error", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(924, "Lỗi tải rolling: " + e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // DTO nội bộ
    // ══════════════════════════════════════════════════════════════

    public record PosStoreDto(
            Long   id,
            String name,
            String address,
            String phone,
            String avatarUrl
    ) {
        static PosStoreDto from(PosStore s) {
            return new PosStoreDto(
                    s.getId(),
                    s.getName(),
                    s.getAddress(),
                    s.getPhone(),
                    s.getAvatarUrl()
            );
        }
    }

    // ══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════

    private DashboardDto.DateRangeFilter buildFilter(String period, Long customFrom, Long customTo) {
        LocalDate today = LocalDate.now(VN_ZONE);
        return switch (period.toUpperCase()) {
            case "TODAY" -> {
                long from = today.atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new DashboardDto.DateRangeFilter(from, to);
            }
            case "7DAYS" -> {
                long from = today.minusDays(6).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new DashboardDto.DateRangeFilter(from, to);
            }
            case "28DAYS" -> {
                long from = today.minusDays(27).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new DashboardDto.DateRangeFilter(from, to);
            }
            case "30DAYS" -> {
                long from = today.minusDays(29).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new DashboardDto.DateRangeFilter(from, to);
            }
            case "MONTH" -> {
                long from = today.withDayOfMonth(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new DashboardDto.DateRangeFilter(from, to);
            }
            case "3MONTHS" -> {
                long from = today.minusMonths(3).withDayOfMonth(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new DashboardDto.DateRangeFilter(from, to);
            }
            case "6MONTHS" -> {
                long from = today.minusMonths(6).withDayOfMonth(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new DashboardDto.DateRangeFilter(from, to);
            }
            case "YEAR" -> {
                long from = today.minusMonths(11).withDayOfMonth(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new DashboardDto.DateRangeFilter(from, to);
            }
            case "CUSTOM" -> new DashboardDto.DateRangeFilter(customFrom, customTo);
            default       -> new DashboardDto.DateRangeFilter(null, null);
        };
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
            case "28DAYS" -> {
                long from = today.minusDays(27).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
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
            case "3MONTHS" -> {
                long from = today.minusMonths(3).withDayOfMonth(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                long to   = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
                yield new PosDashboardDto.DateRangeFilter(from, to);
            }
            case "6MONTHS" -> {
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
                 "6MONTHS" -> "MONTH";
            case "YEAR"    -> "MONTH";
            case "CUSTOM"  -> {
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
    private final TelegramService telegramService;
    private final PosOrderRepository posOrderRepository;

    @GetMapping("/pos-orders/history")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPosOrderHistory(
            @RequestParam(defaultValue = "")    String q,
            @RequestParam(required = false)     Long   fromTs,
            @RequestParam(required = false)     Long   toTs,
            @RequestParam(defaultValue = "0")   int    page,
            @RequestParam(defaultValue = "20")  int    size,
            @RequestParam(required = false)     Long   storeId,
            Authentication auth
    ) {
        try {
            User user = (User) auth.getPrincipal();

            LocalDate today = LocalDate.now(VN_ZONE);
            long resolvedFrom = fromTs != null ? fromTs :
                    today.atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
            long resolvedTo   = toTs   != null ? toTs   :
                    today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;

            String query = (q == null || q.isBlank()) ? null : q.trim();
            Pageable pageable = PageRequest.of(page, size);
            Page<PosOrder> pageResult;

            boolean isSuperAdmin = user.getRole() == Role.SUPERADMIN;

            if (isSuperAdmin && storeId != null) {
                pageResult = posOrderRepository.searchByStore(
                        storeId, resolvedFrom, resolvedTo, query, pageable);
            } else if (isSuperAdmin) {
                pageResult = posOrderRepository.searchAll(
                        resolvedFrom, resolvedTo, query, pageable);
            } else {
                Long resolvedStoreId = extractStoreId(user.getId());
                pageResult = posOrderRepository.searchByStore(
                        resolvedStoreId, resolvedFrom, resolvedTo, query, pageable);
            }

            List<Map<String, Object>> content = pageResult.getContent()
                    .stream().map(this::toPosOrderMap).toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",      content);
            result.put("totalItems",   pageResult.getTotalElements());
            result.put("totalPages",   pageResult.getTotalPages());
            result.put("currentPage",  page);
            result.put("hasNext",      pageResult.hasNext());

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[POS-HISTORY] error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    private Map<String, Object> toPosOrderMap(PosOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            o.getId());
        m.put("orderCode",     o.getOrderCode());
        m.put("orderSource",   o.getOrderSource().name());
        m.put("customerName",  o.getCustomerName());
        m.put("customerPhone", o.getCustomerPhone());
        BigDecimal recalcTotal = o.getItems().stream()
                .map(i -> {
                    boolean isByWeight = i.getSelectedIngredients().stream()
                            .anyMatch(ing -> ing.getUnitWeights() != null
                                    && !ing.getUnitWeights().isEmpty());
                    if (isByWeight && i.getDiscountPercent() != null && i.getDiscountPercent() > 0) {
                        BigDecimal quantityUsed = i.getSelectedIngredients().stream()
                                .filter(ing -> ing.getUnitWeights() != null && !ing.getUnitWeights().isEmpty())
                                .map(ing -> ing.getQuantityUsed())
                                .findFirst()
                                .orElse(BigDecimal.ONE);
                        return i.getDefaultPrice()
                                .multiply(quantityUsed)
                                .multiply(BigDecimal.valueOf(i.getQuantity()));
                    } else if (isByWeight) {
                        return i.getFinalUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity()));
                    } else if (i.getDiscountPercent() != null && i.getDiscountPercent() > 0) {
                        return i.getDefaultPrice()
                                .multiply(BigDecimal.valueOf(i.getQuantity()));
                    } else {
                        return i.getBasePrice()
                                .multiply(BigDecimal.valueOf(i.getQuantity()));
                    }
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        m.put("totalAmount", recalcTotal);
        m.put("discountAmount",o.getDiscountAmount());
        m.put("vatAmount",          o.getTotalVatAmount());
        m.put("platformFeeAmount",  o.getPlatformFeeAmount());
        m.put("platformFeePercent", o.getPlatformRate() != null
                ? o.getPlatformRate().multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO);
        m.put("discountNote",       o.getDiscountNote());
        m.put("finalAmount",   o.getFinalAmount());
        m.put("paymentMethod", o.getPaymentMethod());
        m.put("status",        o.getStatus().name());
        m.put("createdAt",     o.getCreatedAt());
        m.put("itemCount",     o.getItems().size());
        m.put("staffName", o.getShift() != null
                ? o.getShift().getStaffName() : "");

        m.put("items", o.getItems().stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            boolean isByWeight = i.getSelectedIngredients().stream()
                    .anyMatch(ing -> ing.getUnitWeights() != null
                            && !ing.getUnitWeights().isEmpty());

            BigDecimal displayBasePrice;
            if (isByWeight && i.getDiscountPercent() != null && i.getDiscountPercent() > 0) {
                BigDecimal quantityUsed = i.getSelectedIngredients().stream()
                        .filter(ing -> ing.getUnitWeights() != null && !ing.getUnitWeights().isEmpty())
                        .map(ing -> ing.getQuantityUsed())
                        .findFirst()
                        .orElse(BigDecimal.ONE);
                displayBasePrice = i.getDefaultPrice().multiply(quantityUsed);
            } else if (isByWeight) {
                displayBasePrice = i.getFinalUnitPrice();
            } else if (i.getDiscountPercent() != null && i.getDiscountPercent() > 0) {
                displayBasePrice = i.getDefaultPrice();
            } else {
                displayBasePrice = i.getBasePrice();
            }

            im.put("productName",    i.getProductName());
            im.put("quantity",       i.getQuantity());
            im.put("basePrice",      displayBasePrice);
            im.put("isByWeight",     isByWeight);
            im.put("defaultPrice",   i.getDefaultPrice());
            im.put("finalUnitPrice", i.getFinalUnitPrice());
            im.put("discountPercent",i.getDiscountPercent());
            im.put("subtotal",       i.getSubtotal());
            im.put("vatPercent",     i.getVatPercent());
            im.put("vatAmount",      i.getVatAmount());
            im.put("addonAmount",    i.getAddonAmount());
            im.put("note",           i.getNote());
            im.put("categoryName",   i.getCategoryName());

            im.put("ingredients", i.getSelectedIngredients().stream().map(ing -> {
                Map<String, Object> ingm = new LinkedHashMap<>();
                ingm.put("variantGroupName", ing.getVariantGroupName());
                ingm.put("ingredientName",   ing.getIngredientName());
                ingm.put("quantity", ing.getQuantityUsed());
                ingm.put("unit",     ing.getIngredientUnit());
                return ingm;
            }).toList());

            return im;
        }).toList());

        return m;
    }


    private final OrderService orderService;

    @GetMapping("/sale-orders/history")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSaleOrderHistory(
            @RequestParam(defaultValue = "")   String q,
            @RequestParam(required = false)    Long   fromTs,
            @RequestParam(required = false)    Long   toTs,
            @RequestParam(defaultValue = "0")  int    page,
            @RequestParam(defaultValue = "20") int    size
    ) {
        try {
            LocalDate today = LocalDate.now(VN_ZONE);
            long resolvedFrom = fromTs != null ? fromTs :
                    today.atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
            long resolvedTo   = toTs   != null ? toTs   :
                    today.plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;

            Page<OrderResponse> pageResult = orderService.getSaleOrderHistoryAll(
                    resolvedFrom, resolvedTo, q, page, size);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",     pageResult.getContent());
            result.put("totalItems",  pageResult.getTotalElements());
            result.put("totalPages",  pageResult.getTotalPages());
            result.put("currentPage", page);
            result.put("hasNext",     pageResult.hasNext());

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[SUPERADMIN] getSaleOrderHistory error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/sale-orders/{id}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelSaleOrder(@PathVariable Long id) {
        try {
            OrderResponse res = orderService.cancelSaleOrder(id);
            return ResponseEntity.ok(ApiResponse.success(
                    res, "Đã hủy đơn hàng và hoàn kho theo lô giá vốn."));
        } catch (RuntimeException e) {
            log.error("[SUPERADMIN] cancelSaleOrder error: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[SUPERADMIN] cancelSaleOrder unexpected", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/pos/export")
    public ResponseEntity<ApiResponse<String>> exportPosOrders(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false) Long   fromTs,
            @RequestParam(required = false) Long   toTs
    ) {
        final long[] range = resolveTimeRange(period, fromTs, toTs);

        CompletableFuture.runAsync(() -> {
            try {
                byte[] excel = posOrderExportService.exportForSuperAdmin(
                        range[0], range[1]);

                String filename = "orders_all_" + LocalDate.now(VN_ZONE) + ".xlsx";
                String caption  = "📊 Báo cáo đơn hàng POS - Tất cả xe";

                telegramService.sendDocumentByGroupName(
                        "pos", excel, filename, caption, null);

            } catch (Exception e) {
                log.error("[POS] exportPosOrders async error", e);
            }
        });

        return ResponseEntity.ok(ApiResponse.success(
                "Đang tạo báo cáo...",
                "Báo cáo sẽ được gửi vào Telegram"));
    }

    @GetMapping("/dashboard/pos/export/store/{storeId}")
    public ResponseEntity<ApiResponse<String>> exportPosOrdersByStore(
            @PathVariable Long storeId,
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false) Long   fromTs,
            @RequestParam(required = false) Long   toTs
    ) {
        final long[] range = resolveTimeRange(period, fromTs, toTs);

        var storeOpt     = posStoreRepository.findById(storeId);
        String storeName = storeOpt.map(PosStore::getName).orElse(null);
        final String finalStoreName = storeName;

        CompletableFuture.runAsync(() -> {
            try {
                byte[] excel = posOrderExportService.exportForSuperAdmin(
                        storeId, finalStoreName, range[0], range[1]);

                String filename = "orders_store" + storeId
                        + "_" + LocalDate.now(VN_ZONE) + ".xlsx";
                String caption  = "📊 Báo cáo POS"
                        + (finalStoreName != null ? " - " + finalStoreName : "");

                telegramService.sendDocumentByGroupName(
                        "pos", excel, filename, caption, null);

            } catch (Exception e) {
                log.error("[POS] exportPosOrdersByStore async error", e);
            }
        });

        return ResponseEntity.ok(ApiResponse.success(
                "Đang tạo báo cáo...",
                "Báo cáo sẽ được gửi vào Telegram"));
    }

    private long[] resolveTimeRange(String period, Long customFrom, Long customTo) {
        LocalDate today = LocalDate.now(VN_ZONE);
        return switch (period.toUpperCase()) {
            case "TODAY"    -> new long[]{
                    today.atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23,59,59).atZone(VN_ZONE).toInstant().toEpochMilli()};
            case "7DAYS"    -> new long[]{
                    today.minusDays(6).atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23,59,59).atZone(VN_ZONE).toInstant().toEpochMilli()};
            case "28DAYS"   -> new long[]{
                    today.minusDays(27).atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23,59,59).atZone(VN_ZONE).toInstant().toEpochMilli()};
            case "30DAYS"   -> new long[]{
                    today.minusDays(29).atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23,59,59).atZone(VN_ZONE).toInstant().toEpochMilli()};
            case "3MONTHS"  -> new long[]{
                    today.minusMonths(3).withDayOfMonth(1).atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23,59,59).atZone(VN_ZONE).toInstant().toEpochMilli()};
            case "6MONTHS"  -> new long[]{
                    today.minusMonths(6).withDayOfMonth(1).atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23,59,59).atZone(VN_ZONE).toInstant().toEpochMilli()};
            case "YEAR"     -> new long[]{
                    today.minusMonths(11).withDayOfMonth(1).atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23,59,59).atZone(VN_ZONE).toInstant().toEpochMilli()};
            case "CUSTOM"   -> new long[]{
                    customFrom != null ? customFrom : today.atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    customTo   != null ? customTo   : today.atTime(23,59,59).atZone(VN_ZONE).toInstant().toEpochMilli()};
            default         -> new long[]{
                    today.atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23,59,59).atZone(VN_ZONE).toInstant().toEpochMilli()};
        };
    }

    private final SellerOrderExportService sellerOrderExportService;

    @GetMapping("/dashboard/restaurant/export")
    public ResponseEntity<ApiResponse<String>> exportRestaurantOrders(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false) Long   fromTs,
            @RequestParam(required = false) Long   toTs,
            @RequestParam(required = false) String mode
    ) {
        final long[] range = resolveTimeRange(period, fromTs, toTs);

        final LocalDate from = Instant.ofEpochMilli(range[0]).atZone(VN_ZONE).toLocalDate();
        final LocalDate to   = Instant.ofEpochMilli(range[1]).atZone(VN_ZONE).toLocalDate();
        final String modeLabel = "wholesale".equalsIgnoreCase(mode) ? "Sỉ" : "Lẻ";
        final String caption = String.format(
                "📊 Báo cáo đơn hàng %s từ %s đến %s", modeLabel,
                from.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                to.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));

        final String finalMode = mode;
        CompletableFuture.runAsync(() -> {
            try {
                byte[] excel = sellerOrderExportService
                        .exportRestaurantOrders(range[0], range[1], finalMode);
                String filename = "seller_orders_" + LocalDate.now(VN_ZONE) + ".xlsx";
                telegramService.sendDocumentByGroupName(
                        "seller", excel, filename, caption, null);
            } catch (Exception e) {
                log.error("[SELLER] exportRestaurantOrders async error", e);
            }
        });

        return ResponseEntity.ok(ApiResponse.success(
                "Đang tạo báo cáo...", "Báo cáo sẽ được gửi vào Telegram"));
    }

    private final PosChartService posChartService;

    // ══════════════════════════════════════════════════════════════
    // CHART ENDPOINTS
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/dashboard/charts/categories")
    public ResponseEntity<ApiResponse<List<PosChartDto.CategoryItem>>> getChartCategories(
            @RequestParam Long storeId) {
        return ResponseEntity.ok(ApiResponse.success(
                posChartService.getCategories(storeId), "OK"));
    }

    @GetMapping("/dashboard/charts/period-shift")
    public ResponseEntity<ApiResponse<List<PosChartDto.PeriodShiftPoint>>> getPeriodShift(
            @RequestParam Long storeId,
            @RequestParam Long fromTs,
            @RequestParam Long toTs,
            @RequestParam(defaultValue = "MONTH_30") String periodUnit,
            @RequestParam(required = false) List<String> categories) {
        try {
            var data = posChartService.getPeriodByShift(storeId, periodUnit, fromTs, toTs, categories);
            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getPeriodShift error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/charts/period-stacked")
    public ResponseEntity<ApiResponse<List<PosChartDto.PeriodStackedPoint>>> getPeriodStacked(
            @RequestParam Long storeId,
            @RequestParam Long fromTs,
            @RequestParam Long toTs,
            @RequestParam(defaultValue = "MONTH_30") String periodUnit,
            @RequestParam(required = false) List<String> categories,
            Authentication auth) {
        try {
            List<PosChartDto.PeriodStackedPoint> data =
                    posChartService.getPeriodStackedByShift(
                            storeId, periodUnit, fromTs, toTs, categories);
            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getPeriodStacked error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    // ── Heatmap đơn hàng (giữ nguyên) ────────────────────────────

    @GetMapping("/dashboard/charts/heatmap")
    public ResponseEntity<ApiResponse<List<PosChartDto.HeatmapCell>>> getHeatmap(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "60") int periodMinutes,
            @RequestParam(defaultValue = "0") long fromTs,
            @RequestParam(defaultValue = "0") long toTs,
            @RequestParam(required = false) List<Long> productIds) {
        try {
            if (periodMinutes != 30 && periodMinutes != 60 && periodMinutes != 120)
                periodMinutes = 60;
            if (fromTs == 0) {
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
                fromTs = today.minusMonths(1).withDayOfMonth(1)
                        .atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh"))
                        .toInstant().toEpochMilli();
                toTs = today.withDayOfMonth(today.getMonth().length(today.isLeapYear()))
                        .plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh"))
                        .toInstant().toEpochMilli() - 1;
            }
            var result = posChartService.getHeatmap(storeId, periodMinutes, fromTs, toTs, productIds);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getHeatmap error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/charts/products")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getHeatmapProducts(
            @RequestParam Long storeId) {
        try {
            List<Map<String, Object>> products = posChartService.getProductsForHeatmap(storeId);
            return ResponseEntity.ok(ApiResponse.success(products, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getHeatmapProducts error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ← THÊM MỚI: 3 endpoints cho Product Heatmap, Ingredient Heatmap,
    //              và danh sách nguyên liệu chính
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/dashboard/charts/product-heatmap")
    public ResponseEntity<ApiResponse<List<PosChartDto.HeatmapCell>>> getProductHeatmap(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "60") int periodMinutes,
            @RequestParam(defaultValue = "0") long fromTs,
            @RequestParam(defaultValue = "0") long toTs,
            @RequestParam(required = false) List<Long> productIds) {
        try {
            if (periodMinutes != 30 && periodMinutes != 60 && periodMinutes != 120)
                periodMinutes = 60;
            if (fromTs == 0) {
                LocalDate today = LocalDate.now(VN_ZONE);
                fromTs = today.minusMonths(1).withDayOfMonth(1)
                        .atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                toTs = today.withDayOfMonth(today.getMonth().length(today.isLeapYear()))
                        .plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
            }
            var result = posChartService.getProductHeatmap(
                    storeId, periodMinutes, fromTs, toTs, productIds);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getProductHeatmap error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/charts/ingredient-heatmap")
    public ResponseEntity<ApiResponse<List<PosChartDto.HeatmapCell>>> getIngredientHeatmap(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "60") int periodMinutes,
            @RequestParam(defaultValue = "0") long fromTs,
            @RequestParam(defaultValue = "0") long toTs,
            @RequestParam(required = false) List<Long> ingredientIds) {
        try {
            if (periodMinutes != 30 && periodMinutes != 60 && periodMinutes != 120)
                periodMinutes = 60;
            if (fromTs == 0) {
                LocalDate today = LocalDate.now(VN_ZONE);
                fromTs = today.minusMonths(1).withDayOfMonth(1)
                        .atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
                toTs = today.withDayOfMonth(today.getMonth().length(today.isLeapYear()))
                        .plusDays(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli() - 1;
            }
            var result = posChartService.getIngredientHeatmap(
                    storeId, periodMinutes, fromTs, toTs, ingredientIds);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getIngredientHeatmap error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/charts/main-ingredients")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMainIngredients(
            @RequestParam Long storeId) {
        try {
            var result = posChartService.getMainIngredients(storeId);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[CHART] getMainIngredients error", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }
}