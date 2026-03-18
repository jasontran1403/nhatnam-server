package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.DashboardDto;
import com.nhatnam.server.dto.PosDashboardDto;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.pos.PosStore;
import com.nhatnam.server.repository.pos.PosStoreRepository;
import com.nhatnam.server.service.serviceimpl.DashboardService;
import com.nhatnam.server.utils.PosOrderExportService;
import com.nhatnam.server.utils.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/superadmin")
public class SuperAdminController {

    private final DashboardService    dashboardService;
    private final PosStoreRepository  posStoreRepository;

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    // ══════════════════════════════════════════════════════════════
    // POS STORES — GET /api/superadmin/dashboard/pos/vehicles
    // ══════════════════════════════════════════════════════════════

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