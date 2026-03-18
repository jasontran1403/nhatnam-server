package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.DashboardDto;
import com.nhatnam.server.dto.PosDashboardDto;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.pos.PosStoreRepository;
import com.nhatnam.server.repository.pos.PosUserStoreRepository;
import com.nhatnam.server.service.serviceimpl.DashboardService;
import com.nhatnam.server.utils.PosOrderExportService;
import com.nhatnam.server.utils.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.concurrent.CompletableFuture;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/admin")
public class AdminController {

    private final DashboardService dashboardService;
    private final PosUserStoreRepository posUserStoreRepository;

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @GetMapping("/test")
    public ResponseEntity<ApiResponse> test() {
        return ResponseEntity.ok(ApiResponse.success(null, "success"));
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
    private final TelegramService telegramService;

    @GetMapping("/dashboard/pos/export")
    public ResponseEntity<ApiResponse<String>> exportPosOrders(
            @RequestParam(defaultValue = "30DAYS") String period,
            @RequestParam(required = false) Long fromTs,
            @RequestParam(required = false) Long toTs,
            Authentication authentication
    ) {
        try {
            User user = (User) authentication.getPrincipal();

            var storeOptional = posUserStoreRepository.findByUserId(user.getId());
            if (storeOptional.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(922, "Tài khoản chưa được gán store"));
            }
            final Long storeId = storeOptional.get().getStore().getId();
            final long[] range = resolveTimeRange(period, fromTs, toTs);

            String storeName = storeOptional.get().getStore().getName();

            CompletableFuture.runAsync(() -> {
                try {
                    byte[] excel = posOrderExportService.exportForStore(storeId, storeName, range[0], range[1]);

                    String filename = "orders_store" + storeId
                            + "_" + LocalDate.now(VN_ZONE) + ".xlsx";

                    String caption = "📊 Báo cáo đơn hàng POS"
                            + (storeOptional.map(posStore -> " - " + storeName).orElse(""));

                    telegramService.sendDocumentByGroupName("pos", excel, filename, caption, null);

                } catch (Exception e) {
                    log.error("[POS] exportPosOrders async error", e);
                }
            });

            return ResponseEntity.ok(
                    ApiResponse.success("Đang tạo báo cáo...", "Báo cáo sẽ được gửi vào Telegram"));

        } catch (Exception e) {
            log.error("Export error", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(923, "Lỗi export: " + e.getMessage()));
        }
    }

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
}