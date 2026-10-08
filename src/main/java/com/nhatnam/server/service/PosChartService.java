package com.nhatnam.server.service;

import com.nhatnam.server.dto.PosChartDto;
import com.nhatnam.server.entity.pos.PosProduct;
import com.nhatnam.server.repository.pos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class PosChartService {

    private final PosOrderRepository     orderRepo;
    private final PosCategoryRepository  categoryRepo;
    private final PosUserStoreRepository userStoreRepo;
    private final PosProductRepository   productRepo;
    private final PosIngredientRepository ingredientRepo;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final int CUSTOM_MAX_POINTS = 30;
    private static final int CUSTOM_DIVISOR_SEARCH_RANGE = 5;

    // ══════════════════════════════════════════════════════════════
    // PRODUCTS FOR HEATMAP FILTER
    // ══════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getProductsForHeatmap(Long storeId) {
        return productRepo.findByStoreId(storeId)
                .stream()
                .collect(Collectors.toMap(
                        PosProduct::getName,
                        p -> p,
                        (existing, replacement) -> existing
                ))
                .values()
                .stream()
                .sorted(Comparator.comparing(PosProduct::getName))
                .map(p -> Map.<String, Object>of(
                        "id",   p.getId(),
                        "name", p.getName()
                ))
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════
    // MAIN INGREDIENTS FOR HEATMAP FILTER
    // ══════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getMainIngredients(Long storeId) {
        return ingredientRepo.findByStoreIdAndIngredientTypeAndIsActiveTrue(
                        storeId, com.nhatnam.server.enumtype.IngredientType.MAIN)
                .stream()
                .sorted(Comparator.comparing(ing -> ing.getName()))
                .map(ing -> Map.<String, Object>of(
                        "id",   ing.getId(),
                        "name", ing.getName()
                ))
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════
    // CATEGORIES
    // ══════════════════════════════════════════════════════════════

    public List<PosChartDto.CategoryItem> getCategories(Long storeId) {
        return categoryRepo
                .findByStoreIdAndIsActiveTrueOrderByDisplayOrderAsc(storeId)
                .stream()
                .map(c -> new PosChartDto.CategoryItem(c.getId(), c.getName()))
                .toList();
    }

    // ══════════════════════════════════════════════════════════════
    // PERIOD BUILDER
    // ══════════════════════════════════════════════════════════════

    public List<long[]> buildPeriods(long fromTs, long toTs, String periodUnit) {
        List<long[]> periods = new ArrayList<>();
        String unit = periodUnit == null ? "" : periodUnit.toUpperCase();

        if (unit.equals("INTRADAY")) {
            return buildIntradayPeriods(fromTs);
        }
        if (unit.equals("CUSTOM")) {
            return buildCustomPeriods(fromTs, toTs);
        }

        switch (unit) {
            case "DAY", "MONTH_30" -> {
                LocalDate cur = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate();
                LocalDate end = Instant.ofEpochMilli(toTs).atZone(VN).toLocalDate();
                while (!cur.isAfter(end)) {
                    long pFrom = cur.atStartOfDay(VN).toInstant().toEpochMilli();
                    long pTo   = cur.plusDays(1).atStartOfDay(VN).toInstant().toEpochMilli() - 1;
                    periods.add(new long[]{pFrom, pTo});
                    cur = cur.plusDays(1);
                }
            }
            case "WEEK" -> {
                LocalDate cur = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate()
                        .with(java.time.temporal.WeekFields.ISO.dayOfWeek(), 1);
                LocalDate end = Instant.ofEpochMilli(toTs).atZone(VN).toLocalDate();
                while (!cur.isAfter(end)) {
                    long pFrom = cur.atStartOfDay(VN).toInstant().toEpochMilli();
                    long pTo   = cur.plusWeeks(1).atStartOfDay(VN).toInstant().toEpochMilli() - 1;
                    periods.add(new long[]{pFrom, pTo});
                    cur = cur.plusWeeks(1);
                }
            }
            case "MONTH", "MONTH_3", "MONTH_6", "3MONTHS", "6MONTHS" -> {
                LocalDate cur = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate().withDayOfMonth(1);
                LocalDate end = Instant.ofEpochMilli(toTs).atZone(VN).toLocalDate();
                while (!cur.isAfter(end)) {
                    long pFrom = cur.atStartOfDay(VN).toInstant().toEpochMilli();
                    long pTo   = cur.plusMonths(1).atStartOfDay(VN).toInstant().toEpochMilli() - 1;
                    periods.add(new long[]{pFrom, pTo});
                    cur = cur.plusMonths(1);
                }
            }
            case "YEAR" -> {
                LocalDate cur = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate().withDayOfYear(1);
                LocalDate end = Instant.ofEpochMilli(toTs).atZone(VN).toLocalDate();
                while (!cur.isAfter(end)) {
                    long pFrom = cur.atStartOfDay(VN).toInstant().toEpochMilli();
                    long pTo   = cur.plusYears(1).atStartOfDay(VN).toInstant().toEpochMilli() - 1;
                    periods.add(new long[]{pFrom, pTo});
                    cur = cur.plusYears(1);
                }
            }
            default -> {
                periods.add(new long[]{fromTs, toTs});
            }
        }

        if (periods.size() < 7 && (unit.equals("DAY") || unit.equals("MONTH_30"))) {
            return buildMultiDayIntradayPeriods(fromTs, toTs);
        }

        return periods;
    }

    private List<long[]> buildIntradayPeriods(long fromTs) {
        List<long[]> periods = new ArrayList<>();

        LocalDate day   = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate();
        LocalDate today = LocalDate.now(VN);
        boolean isToday = day.isEqual(today);

        final int START_MIN = 7 * 60;
        final int MAX_END   = 23 * 60;
        int endMin;

        if (isToday) {
            ZonedDateTime now = ZonedDateTime.now(VN);
            int nowMin = now.getHour() * 60 + now.getMinute();
            endMin = (nowMin % 60 == 0) ? nowMin : ((nowMin / 60) + 1) * 60;
            endMin = Math.max(endMin, START_MIN + 60);
            endMin = Math.min(endMin, MAX_END);
        } else {
            endMin = MAX_END;
        }

        int totalMin = endMin - START_MIN;

        int slotMin = 180;
        if (isToday) {
            if (ceilDiv(totalMin, slotMin) < 7) slotMin = 60;
            if (ceilDiv(totalMin, slotMin) < 7) slotMin = 30;
        }

        for (int m = START_MIN; m < endMin; m += slotMin) {
            int slotEnd = Math.min(m + slotMin, endMin);
            long pFrom = day.atStartOfDay(VN).plusMinutes(m).toInstant().toEpochMilli();
            long pTo   = day.atStartOfDay(VN).plusMinutes(slotEnd).toInstant().toEpochMilli() - 1;
            periods.add(new long[]{pFrom, pTo});
        }
        return periods;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private List<long[]> buildCustomPeriods(long fromTs, long toTs) {
        List<long[]> periods = new ArrayList<>();

        LocalDate fromDate = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate();
        LocalDate toDate   = Instant.ofEpochMilli(toTs).atZone(VN).toLocalDate();

        long totalDays = toDate.toEpochDay() - fromDate.toEpochDay() + 1;
        if (totalDays <= 0) totalDays = 1;

        int bucketSize = resolveCustomBucketSize((int) totalDays);

        LocalDate cur = fromDate;
        while (!cur.isAfter(toDate)) {
            LocalDate bucketEnd = cur.plusDays(bucketSize - 1);
            if (bucketEnd.isAfter(toDate)) bucketEnd = toDate;

            long pFrom = cur.atStartOfDay(VN).toInstant().toEpochMilli();
            long pTo   = bucketEnd.plusDays(1).atStartOfDay(VN).toInstant().toEpochMilli() - 1;
            periods.add(new long[]{pFrom, pTo});

            cur = bucketEnd.plusDays(1);
        }

        if (periods.size() < 7) {
            return buildMultiDayIntradayPeriods(fromTs, toTs);
        }

        return periods;
    }

    private int resolveCustomBucketSize(int totalDays) {
        if (totalDays <= CUSTOM_MAX_POINTS) return 1;

        int b0 = (int) Math.ceil(totalDays / (double) CUSTOM_MAX_POINTS);

        for (int b = b0; b <= b0 + CUSTOM_DIVISOR_SEARCH_RANGE; b++) {
            if (totalDays % b == 0) return b;
        }
        return b0;
    }

    private List<long[]> buildMultiDayIntradayPeriods(long fromTs, long toTs) {
        LocalDate fromDate = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate();
        LocalDate toDate   = Instant.ofEpochMilli(toTs).atZone(VN).toLocalDate();
        LocalDate today    = LocalDate.now(VN);
        if (toDate.isAfter(today)) toDate = today;

        final int START_MIN = 7 * 60;
        final int MAX_END   = 23 * 60;
        int[] candidates = {360, 240, 180, 120, 60, 30};

        // ── Tìm slot size phù hợp ──────────────────────────────
        int slotMin = 120; // fallback
        for (int c : candidates) {
            int totalSlots = 0;
            LocalDate d = fromDate;
            while (!d.isAfter(toDate)) {
                int dayEnd = resolveEndMin(d, today, START_MIN, MAX_END);
                totalSlots += ceilDiv(dayEnd - START_MIN, c);
                d = d.plusDays(1);
            }
            if (totalSlots >= 7) {
                slotMin = c;
                break;
            }
        }

        // ── Build periods ───────────────────────────────────────
        List<long[]> periods = new ArrayList<>();
        LocalDate d = fromDate;
        while (!d.isAfter(toDate)) {
            int dayEnd = resolveEndMin(d, today, START_MIN, MAX_END);
            for (int m = START_MIN; m < dayEnd; m += slotMin) {
                int slotEnd = Math.min(m + slotMin, dayEnd);
                long pFrom = d.atStartOfDay(VN).plusMinutes(m).toInstant().toEpochMilli();
                long pTo   = d.atStartOfDay(VN).plusMinutes(slotEnd).toInstant().toEpochMilli() - 1;
                periods.add(new long[]{pFrom, pTo});
            }
            d = d.plusDays(1);
        }
        return periods;
    }

    /**
     * Tính endMin cho 1 ngày: nếu là hôm nay → làm tròn lên giờ kế.
     */
    private int resolveEndMin(LocalDate day, LocalDate today, int startMin, int maxEnd) {
        if (day.isEqual(today)) {
            ZonedDateTime now = ZonedDateTime.now(VN);
            int nowMin = now.getHour() * 60 + now.getMinute();
            int endMin = (nowMin % 60 == 0) ? nowMin : ((nowMin / 60) + 1) * 60;
            endMin = Math.max(endMin, startMin + 60);
            return Math.min(endMin, maxEnd);
        }
        return maxEnd;
    }

    // ══════════════════════════════════════════════════════════════
    // PERIOD LABEL
    // ══════════════════════════════════════════════════════════════

    public String buildPeriodLabel(long fromTs, long toTs, String periodUnit) {
        long spanMs = toTs - fromTs + 1;
        if (spanMs < 24L * 60 * 60 * 1000) {
            ZonedDateTime fromZdt = Instant.ofEpochMilli(fromTs).atZone(VN);
            ZonedDateTime toZdt   = Instant.ofEpochMilli(toTs + 1).atZone(VN);

            String unit = periodUnit == null ? "" : periodUnit.toUpperCase();
            if (unit.equals("INTRADAY")) {
                // Single day → chỉ show khung giờ
                return String.format("%d:%02d-%d:%02d",
                        fromZdt.getHour(), fromZdt.getMinute(),
                        toZdt.getHour(), toZdt.getMinute());
            }
            // Multi-day intraday → show ngày + khung giờ
            return String.format("%02d/%02d %d:%02d-%d:%02d",
                    fromZdt.getDayOfMonth(), fromZdt.getMonthValue(),
                    fromZdt.getHour(), fromZdt.getMinute(),
                    toZdt.getHour(), toZdt.getMinute());
        }

        LocalDate from = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate();
        LocalDate to   = Instant.ofEpochMilli(Math.min(toTs, System.currentTimeMillis()))
                .atZone(VN).toLocalDate();

        String unit = periodUnit == null ? "" : periodUnit.toUpperCase();

        if (unit.equals("INTRADAY")) {
            ZonedDateTime fromZdt = Instant.ofEpochMilli(fromTs).atZone(VN);
            ZonedDateTime toZdt   = Instant.ofEpochMilli(toTs + 1).atZone(VN);
            return String.format("%d:%02d-%d:%02d",
                    fromZdt.getHour(), fromZdt.getMinute(),
                    toZdt.getHour(), toZdt.getMinute());
        }

        if (unit.equals("CUSTOM")) {
            if (from.isEqual(to)) {
                return from.format(DateTimeFormatter.ofPattern("dd/MM"));
            }
            return from.format(DateTimeFormatter.ofPattern("dd/MM"))
                    + "-" + to.format(DateTimeFormatter.ofPattern("dd/MM"));
        }

        return switch (unit) {
            case "DAY", "MONTH_30" ->
                    from.format(DateTimeFormatter.ofPattern("dd/MM"));
            case "WEEK" ->
                    from.format(DateTimeFormatter.ofPattern("dd/MM"))
                            + "-" + to.format(DateTimeFormatter.ofPattern("dd/MM"));
            case "MONTH", "MONTH_3", "MONTH_6", "3MONTHS", "6MONTHS" ->
                    "T" + from.getMonthValue() + "/" + (from.getYear() % 100);
            case "YEAR" ->
                    String.valueOf(from.getYear());
            default ->
                    from.format(DateTimeFormatter.ofPattern("dd/MM"));
        };
    }

    public String buildPeriodLabel(long fromTs, long toTs) {
        LocalDate from = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate();
        LocalDate to   = Instant.ofEpochMilli(toTs - 1).atZone(VN).toLocalDate();
        long spanDays  = to.toEpochDay() - from.toEpochDay() + 1;

        if (spanDays <= 1)        return to.format(DateTimeFormatter.ofPattern("dd/MM"));
        else if (spanDays <= 8)   return from.format(DateTimeFormatter.ofPattern("dd/MM"))
                + "-" + to.format(DateTimeFormatter.ofPattern("dd/MM"));
        else if (spanDays <= 32)  return "T" + to.getMonthValue() + "/" + (to.getYear() % 100);
        else if (spanDays <= 95)  { int q = (to.getMonthValue() - 1) / 3 + 1;
            return "Q" + q + "/" + (to.getYear() % 100); }
        else if (spanDays <= 185) { int h = to.getMonthValue() <= 6 ? 1 : 2;
            return "H" + h + "/" + (to.getYear() % 100); }
        else                      return String.valueOf(to.getYear());
    }

    // ══════════════════════════════════════════════════════════════
    // CHART 1: Revenue + OrderCount by Shift
    // ══════════════════════════════════════════════════════════════

    public List<PosChartDto.PeriodShiftPoint> getPeriodByShift(
            Long storeId,
            String periodUnit,
            long currentFromTs,
            long currentToTs,
            List<String> categoryNames) {

        List<long[]> periods = buildPeriods(currentFromTs, currentToTs, periodUnit);
        List<String> cats = (categoryNames == null || categoryNames.isEmpty()) ? null : categoryNames;

        List<Integer> allShifts      = List.of(1, 2, 3);
        List<String>  allShiftLabels = List.of("1 [5h-12h]", "2 [12h-17h]", "3 [17h-22h]");

        List<PosChartDto.PeriodShiftPoint> result = new ArrayList<>();

        for (long[] period : periods) {
            long pFrom = period[0];
            long pTo   = period[1];
            String label = buildPeriodLabel(pFrom, pTo, periodUnit);

            List<Object[]> rows = orderRepo.findByShiftInRange(storeId, pFrom, pTo, cats);

            Map<Integer, double[]> dataMap = new HashMap<>();
            for (Object[] row : rows) {
                int    shift = ((Number) row[0]).intValue();
                double rev   = row[1] != null ? ((Number) row[1]).doubleValue() : 0;
                double cnt   = row[2] != null ? ((Number) row[2]).doubleValue() : 0;
                dataMap.put(shift, new double[]{rev, cnt});
            }

            for (int i = 0; i < allShifts.size(); i++) {
                int    shift      = allShifts.get(i);
                String shiftLabel = allShiftLabels.get(i);
                double[] d        = dataMap.getOrDefault(shift, new double[]{0, 0});
                result.add(new PosChartDto.PeriodShiftPoint(
                        label, pFrom, pTo, shift, shiftLabel, d[0], d[1]));
            }
        }

        return result;
    }

    // ══════════════════════════════════════════════════════════════
    // CHART 2: Stacked by Shift
    // ══════════════════════════════════════════════════════════════

    public List<PosChartDto.PeriodStackedPoint> getPeriodStackedByShift(
            Long storeId,
            String periodUnit,
            long currentFromTs,
            long currentToTs,
            List<String> categoryNames) {

        List<long[]> periods = buildPeriods(currentFromTs, currentToTs, periodUnit);
        List<String> cats    = (categoryNames == null || categoryNames.isEmpty()) ? null : categoryNames;

        List<String> allShifts = List.of("1 [5h-12h]", "2 [12h-17h]", "3 [17h-22h]");

        List<PosChartDto.PeriodStackedPoint> result = new ArrayList<>();

        for (long[] period : periods) {
            long pFrom = period[0];
            long pTo   = period[1];
            String label = buildPeriodLabel(pFrom, pTo, periodUnit);

            List<Object[]> rows = orderRepo.findByShiftStackedInRange(storeId, pFrom, pTo, cats);

            Map<String, double[]> dataMap = new LinkedHashMap<>();
            for (Object[] row : rows) {
                String shiftLabel = (String) row[0];
                double revenue    = row[1] != null ? ((Number) row[1]).doubleValue() : 0;
                double orderCount = row[2] != null ? ((Number) row[2]).doubleValue() : 0;
                dataMap.put(shiftLabel, new double[]{revenue, orderCount});
            }

            for (String shift : allShifts) {
                double[] d = dataMap.getOrDefault(shift, new double[]{0, 0});
                result.add(new PosChartDto.PeriodStackedPoint(
                        label, pFrom, pTo, shift, "SHIFT", d[0], d[1]));
            }
        }

        return result;
    }

    // ══════════════════════════════════════════════════════════════
    // CHART 2: Stacked by Category
    // ══════════════════════════════════════════════════════════════

    public List<PosChartDto.PeriodStackedPoint> getPeriodStackedByCategory(
            Long storeId,
            String periodUnit,
            long currentFromTs,
            long currentToTs,
            List<String> categoryNames) {

        List<long[]> periods = buildPeriods(currentFromTs, currentToTs, periodUnit);
        List<String> cats = (categoryNames == null || categoryNames.isEmpty())
                ? List.of() : categoryNames;

        List<PosChartDto.PeriodStackedPoint> result = new ArrayList<>();

        for (long[] period : periods) {
            long pFrom = period[0];
            long pTo   = period[1];
            String label = buildPeriodLabel(pFrom, pTo, periodUnit);

            List<Object[]> rows = cats.isEmpty()
                    ? List.of()
                    : orderRepo.findByCategoryInRange(storeId, pFrom, pTo, cats);

            Map<String, double[]> dataMap = new LinkedHashMap<>();
            for (Object[] row : rows) {
                String catName    = (String) row[0];
                double revenue    = row[1] != null ? ((Number) row[1]).doubleValue() : 0;
                double orderCount = row[2] != null ? ((Number) row[2]).doubleValue() : 0;
                dataMap.put(catName, new double[]{revenue, orderCount});
            }

            for (String cat : cats) {
                double[] d = dataMap.getOrDefault(cat, new double[]{0, 0});
                result.add(new PosChartDto.PeriodStackedPoint(
                        label, pFrom, pTo, cat, "CATEGORY", d[0], d[1]));
            }

            if (cats.isEmpty()) {
                result.add(new PosChartDto.PeriodStackedPoint(
                        label, pFrom, pTo, "N/A", "CATEGORY", 0, 0));
            }
        }

        return result;
    }

    // ══════════════════════════════════════════════════════════════
    // ORDER HEATMAP (refactored to use buildHeatmapFromRaw)
    // ══════════════════════════════════════════════════════════════

    public List<PosChartDto.HeatmapCell> getHeatmap(Long storeId, int periodMinutes,
                                                    long fromTs, long toTs,
                                                    List<Long> productIds) {
        List<String> productNames = null;
        if (productIds != null && !productIds.isEmpty()) {
            productNames = productRepo.findAllById(productIds)
                    .stream().map(PosProduct::getName)
                    .distinct().collect(Collectors.toList());
        }

        final List<String> finalNames = productNames;
        List<Object[]> raw = (finalNames == null || finalNames.isEmpty())
                ? orderRepo.findHeatmapDataByMinute(storeId, fromTs, toTs)
                : orderRepo.findHeatmapDataByMinuteAndProductNames(storeId, fromTs, toTs, finalNames);

        return buildHeatmapFromRaw(raw, fromTs, toTs, periodMinutes);
    }

    // ══════════════════════════════════════════════════════════════
    // PRODUCT HEATMAP — SUM(quantity) per slot
    // ══════════════════════════════════════════════════════════════

    public List<PosChartDto.HeatmapCell> getProductHeatmap(
            Long storeId, int periodMinutes,
            long fromTs, long toTs, List<Long> productIds) {

        List<String> productNames = null;
        if (productIds != null && !productIds.isEmpty()) {
            productNames = productRepo.findAllById(productIds)
                    .stream().map(PosProduct::getName)
                    .distinct().collect(Collectors.toList());
        }

        final List<String> names = productNames;
        List<Object[]> raw = (names == null || names.isEmpty())
                ? orderRepo.findProductHeatmapByMinute(storeId, fromTs, toTs)
                : orderRepo.findProductHeatmapByMinuteAndProductNames(storeId, fromTs, toTs, names);

        return buildHeatmapFromRaw(raw, fromTs, toTs, periodMinutes);
    }

    // ══════════════════════════════════════════════════════════════
    // INGREDIENT HEATMAP — SUM(selected_count × quantity) per slot
    // ══════════════════════════════════════════════════════════════

    public List<PosChartDto.HeatmapCell> getIngredientHeatmap(
            Long storeId, int periodMinutes,
            long fromTs, long toTs, List<Long> ingredientIds) {

        List<Object[]> raw = (ingredientIds == null || ingredientIds.isEmpty())
                ? orderRepo.findIngredientHeatmapByMinute(storeId, fromTs, toTs)
                : orderRepo.findIngredientHeatmapByMinuteAndIds(storeId, fromTs, toTs, ingredientIds);

        return buildHeatmapFromRaw(raw, fromTs, toTs, periodMinutes);
    }

    // ══════════════════════════════════════════════════════════════
    // SHARED: build HeatmapCell list from raw query results
    // raw format: [date, minute_of_day, count, revenue]
    // ══════════════════════════════════════════════════════════════

    private List<PosChartDto.HeatmapCell> buildHeatmapFromRaw(
            List<Object[]> raw, long fromTs, long toTs, int periodMinutes) {

        final int START_MIN       = 7 * 60;
        final int DEFAULT_END_MIN = 22 * 60;
        final int DAY_END_MIN     = 24 * 60;

        LocalDate today    = LocalDate.now(VN);
        LocalDate fromDate = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate();
        LocalDate toDate   = Instant.ofEpochMilli(toTs).atZone(VN).toLocalDate();
        if (toDate.isAfter(today)) toDate = today;

        // Nới khung giờ theo dữ liệu
        int latestSlotStart = -1;
        for (Object[] row : raw) {
            LocalDate ld = LocalDate.parse(row[0].toString());
            if (ld.isAfter(today) || ld.isBefore(fromDate)) continue;
            int minute = ((Number) row[1]).intValue();
            double cnt = ((Number) row[2]).doubleValue();
            if (cnt <= 0 || minute < START_MIN || minute >= DAY_END_MIN) continue;
            int slotStart = START_MIN + ((minute - START_MIN) / periodMinutes) * periodMinutes;
            if (slotStart > latestSlotStart) latestSlotStart = slotStart;
        }

        int endMin = DEFAULT_END_MIN;
        if (latestSlotStart >= 0) endMin = Math.max(endMin, latestSlotStart + periodMinutes);
        endMin = Math.min(endMin, DAY_END_MIN);

        final int END_MIN    = endMin;
        final int totalSlots = (int) Math.ceil((END_MIN - START_MIN) / (double) periodMinutes);

        // Gom theo ngày
        Map<LocalDate, Double> countByDate = new LinkedHashMap<>();
        for (Object[] row : raw) {
            LocalDate ld = LocalDate.parse(row[0].toString());
            if (ld.isAfter(today) || ld.isBefore(fromDate)) continue;
            double cnt = ((Number) row[2]).doubleValue();
            countByDate.merge(ld, cnt, Double::sum);
        }

        Map<Integer, Set<LocalDate>> dowDates = new HashMap<>();
        for (LocalDate ld : countByDate.keySet()) {
            int dow = ld.getDayOfWeek().getValue();
            dowDates.computeIfAbsent(dow, k -> new HashSet<>()).add(ld);
        }

        // Gom theo dow + slot
        Map<String, double[]> rawSum = new LinkedHashMap<>();
        for (Object[] row : raw) {
            LocalDate ld = LocalDate.parse(row[0].toString());
            if (ld.isAfter(today) || ld.isBefore(fromDate)) continue;
            int minute = ((Number) row[1]).intValue();
            if (minute < START_MIN || minute >= END_MIN) continue;
            double cnt = ((Number) row[2]).doubleValue();
            double rev = ((Number) row[3]).doubleValue();
            int dow     = ld.getDayOfWeek().getValue();
            int slotIdx = (minute - START_MIN) / periodMinutes;
            String key  = dow + "_" + slotIdx;
            rawSum.computeIfAbsent(key, k -> new double[]{0, 0});
            rawSum.get(key)[0] += cnt;
            rawSum.get(key)[1] += rev;
        }

        // Tính trung bình
        Map<String, double[]> avgMap = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : rawSum.entrySet()) {
            int dow = Integer.parseInt(e.getKey().split("_")[0]);
            int occ = dowDates.getOrDefault(dow, Set.of()).size();
            if (occ == 0) continue;
            avgMap.put(e.getKey(), new double[]{
                    e.getValue()[0] / occ,
                    e.getValue()[1] / occ
            });
        }

        double maxCell = avgMap.values().stream().mapToDouble(v -> v[0]).max().orElse(1.0);
        double step = maxCell / 9.0;
        if (step <= 0) step = 0.1;

        Set<Integer> dowsWithData = dowDates.keySet();
        String[] dowNames = {"", "Thứ 2", "Thứ 3", "Thứ 4",
                "Thứ 5", "Thứ 6", "Thứ 7", "CN"};

        List<PosChartDto.HeatmapCell> result = new ArrayList<>();
        for (int dow = 1; dow <= 7; dow++) {
            boolean hasData = dowsWithData.contains(dow);
            for (int si = 0; si < totalSlots; si++) {
                int slotStartMin = START_MIN + si * periodMinutes;
                int slotEndMin   = Math.min(slotStartMin + periodMinutes, DAY_END_MIN);
                String hourLabel = String.format("%02d:%02d-%02d:%02d",
                        slotStartMin / 60, slotStartMin % 60,
                        slotEndMin / 60, slotEndMin % 60);
                String   key = dow + "_" + si;
                double[] avg = avgMap.getOrDefault(key, new double[]{0, 0});
                result.add(new PosChartDto.HeatmapCell(
                        "DOW_" + dow, dowNames[dow],
                        slotStartMin, hourLabel,
                        avg[0], avg[1], hasData, step
                ));
            }
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════
    // RESOLVE STORE ID
    // ══════════════════════════════════════════════════════════════

    public Long resolveStoreId(Long userId, Long requestedStoreId) {
        if (requestedStoreId != null) return requestedStoreId;
        return userStoreRepo.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Chưa gán store"))
                .getStore().getId();
    }
}