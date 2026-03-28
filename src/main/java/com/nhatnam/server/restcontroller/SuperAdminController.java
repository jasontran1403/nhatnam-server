package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.DashboardDto;
import com.nhatnam.server.dto.PosDashboardDto;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.pos.PosCustomer;
import com.nhatnam.server.entity.pos.PosStore;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.pos.PosCustomerRepository;
import com.nhatnam.server.repository.pos.PosStoreRepository;
import com.nhatnam.server.service.PosCustomerService;
import com.nhatnam.server.service.serviceimpl.DashboardService;
import com.nhatnam.server.utils.PosOrderExportService;
import com.nhatnam.server.utils.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    private final DashboardService    dashboardService;
    private final PosStoreRepository  posStoreRepository;
    private final PosCustomerRepository posCustomerRepo; // inject thêm
    private final PosCustomerService posCustomerService;
    private final CustomerRepository customerRepository;  // cho B2B

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

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

    // ── POS: tạo mới (chỉ định storeId) ──────────────────────────
    // POST /api/superadmin/pos-customers
    @PostMapping("/pos-customers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPosCustomer(
            @RequestBody Map<String, Object> req) {
        try {
            String phone   = (String) req.get("phone");
            String name    = (String) req.get("name");
            Long   storeId = req.get("storeId") != null
                    ? ((Number) req.get("storeId")).longValue() : null;
            if (phone == null || phone.isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu phone"));
            if (name == null || name.isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu name"));
            if (storeId == null)
                return ResponseEntity.ok(ApiResponse.error(400, "Thiếu storeId"));

            PosCustomer c = posCustomerService.createOrUpdate(
                    phone, name, storeId,
                    (String) req.get("dateOfBirth"),
                    (String) req.get("deliveryAddress"),
                    (String) req.get("referredByPhone"));

            return ResponseEntity.ok(ApiResponse.success(_toPosMap(c), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    // ── POS: cập nhật ─────────────────────────────────────────────
    // PUT /api/superadmin/pos-customers/{id}
    @PutMapping("/pos-customers/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updatePosCustomer(
            @PathVariable Long id,
            @RequestBody Map<String, String> req) {
        try {
            PosCustomer c = posCustomerRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy #" + id));
            if (req.containsKey("name") && req.get("name") != null)
                c.setName(req.get("name").trim());
            if (req.containsKey("deliveryAddress"))
                c.setDeliveryAddress(req.get("deliveryAddress"));
            if (req.containsKey("dateOfBirth"))
                c.setDateOfBirth(req.get("dateOfBirth"));
            c = posCustomerRepo.save(c);
            return ResponseEntity.ok(ApiResponse.success(_toPosMap(c), "Cập nhật thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    // ── B2B: lấy list (full, tất cả) ─────────────────────────────
    // GET /api/superadmin/b2b-customers
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

    // ── B2B: lấy chi tiết ────────────────────────────────────────
    // GET /api/superadmin/b2b-customers/{id}
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

    // ── B2B: tạo mới ─────────────────────────────────────────────
    // POST /api/superadmin/b2b-customers
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

    // ── B2B: cập nhật ─────────────────────────────────────────────
    // PUT /api/superadmin/b2b-customers/{id}
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
        System.out.println(c.getStoreId());
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
        return m;
    }

    // ══════════════════════════════════════════════════════════════
    // POS STORES — GET /api/superadmin/dashboard/pos/vehicles
    // ══════════════════════════════════════════════════════════════

    // GET /api/superadmin/pos-customers — tất cả stores
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
                .findAllByActiveTrueOrderByNameAsc()
                .stream()
                .map(PosStoreDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    // ══════════════════════════════════════════════════════════════
    // RESTAURANT DASHBOARD — GET /api/superadmin/dashboard/restaurant
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/dashboard/restaurant")
    public ResponseEntity<ApiResponse<DashboardDto.RestaurantDashboard>> getRestaurantDashboard(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false)        Long   fromTs,
            @RequestParam(required = false)        Long   toTs,
            @RequestParam(required = false)        String gran,
            @RequestParam(required = false)        String mode
    ) {
        try {
            DashboardDto.DateRangeFilter filter      = buildFilter(period, fromTs, toTs);
            String granularity = gran != null ? gran : defaultGranularity(period, fromTs, toTs);

            DashboardDto.RestaurantDashboard data =
                    dashboardService.getRestaurantDashboard(filter, granularity);

            return ResponseEntity.ok(ApiResponse.success(data, "Dashboard loaded"));

        } catch (Exception e) {
            log.error("Dashboard error", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(921, "Lỗi tải dashboard: " + e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // POS DASHBOARD — GET /api/superadmin/dashboard/pos
    // Nhận thêm vehicleId (optional) — logic/data giữ nguyên
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
            @RequestParam(required = false)        String filterType   // ← THÊM: ALL/TAKE_AWAY/DINE_IN/SHOPEE_FOOD/GRAB_FOOD/CAT_HOT/CAT_COLD/CAT_COMBO
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

    // ══════════════════════════════════════════════════════════════
    // DTO nội bộ — map PosStore → response JSON
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
    private final TelegramService telegramService;

    @GetMapping("/dashboard/pos/export")
    public ResponseEntity<ApiResponse<String>> exportPosOrders(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false) Long   fromTs,
            @RequestParam(required = false) Long   toTs
    ) {
        final long[] range = resolveTimeRange(period, fromTs, toTs);

        // Async — trả về ngay, gửi Telegram sau
        CompletableFuture.runAsync(() -> {
            try {
                // Export TẤT CẢ stores (không truyền storeId)
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

    // ── Export 1 store cụ thể (giữ để dùng sau) ─────────────────
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

    // Dùng chung resolveTimeRange (copy giống AdminController)
    private long[] resolveTimeRange(String period, Long customFrom, Long customTo) {
        LocalDate today = LocalDate.now(VN_ZONE);
        return switch (period.toUpperCase()) {
            case "TODAY"    -> new long[]{
                    today.atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
                    today.atTime(23,59,59).atZone(VN_ZONE).toInstant().toEpochMilli()};
            case "7DAYS"    -> new long[]{
                    today.minusDays(6).atTime(0,0,1).atZone(VN_ZONE).toInstant().toEpochMilli(),
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
}