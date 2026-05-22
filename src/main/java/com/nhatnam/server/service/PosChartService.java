package com.nhatnam.server.service;

import com.nhatnam.server.dto.PosChartDto;
import com.nhatnam.server.entity.pos.PosProduct;
import com.nhatnam.server.repository.pos.PosCategoryRepository;
import com.nhatnam.server.repository.pos.PosOrderRepository;
import com.nhatnam.server.repository.pos.PosProductRepository;
import com.nhatnam.server.repository.pos.PosUserStoreRepository;
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

    private final PosOrderRepository    orderRepo;
    private final PosCategoryRepository categoryRepo;
    private final PosUserStoreRepository userStoreRepo;
    private final PosProductRepository productRepo;

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

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    public List<PosChartDto.CategoryItem> getCategories(Long storeId) {
        return categoryRepo
                .findByStoreIdAndIsActiveTrueOrderByDisplayOrderAsc(storeId)
                .stream()
                .map(c -> new PosChartDto.CategoryItem(c.getId(), c.getName()))
                .toList();
    }

    // ══════════════════════════════════════════════════════════════
    // PERIOD BUILDER — build danh sách bucket theo periodUnit
    // trong đúng range fromTs → toTs
    // ══════════════════════════════════════════════════════════════

    public List<long[]> buildPeriods(long fromTs, long toTs, String periodUnit) {
        List<long[]> periods = new ArrayList<>();

        switch (periodUnit.toUpperCase()) {
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
                // fallback: 1 period = toàn bộ range
                periods.add(new long[]{fromTs, toTs});
            }
        }
        return periods;
    }

    // Label dựa theo periodUnit (không cần tự đoán từ span nữa)
    public String buildPeriodLabel(long fromTs, long toTs, String periodUnit) {
        LocalDate from = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate();
        LocalDate to   = Instant.ofEpochMilli(Math.min(toTs, System.currentTimeMillis()))
                .atZone(VN).toLocalDate();

        return switch (periodUnit.toUpperCase()) {
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

    // Giữ lại method cũ để không break code khác (nếu có)
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

        // ── FIX: build periods đúng theo fromTs/toTs + periodUnit ──
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

        // ── FIX: build periods đúng theo fromTs/toTs + periodUnit ──
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
    // HEATMAP
    // ══════════════════════════════════════════════════════════════
    public List<PosChartDto.HeatmapCell> getHeatmap(Long storeId, int periodMinutes,
                                                    long fromTs, long toTs,
                                                    List<Long> productIds) {
        final int START_MIN  = 7 * 60;
        final int END_MIN    = 22 * 60;
        final int totalSlots = (END_MIN - START_MIN) / periodMinutes;

        LocalDate today    = LocalDate.now(VN);
        LocalDate fromDate = Instant.ofEpochMilli(fromTs).atZone(VN).toLocalDate();
        LocalDate toDate   = Instant.ofEpochMilli(toTs).atZone(VN).toLocalDate();
        if (toDate.isAfter(today)) toDate = today;

        List<String> productNames = null;
        if (productIds != null && !productIds.isEmpty()) {
            productNames = productRepo.findAllById(productIds)
                    .stream()
                    .map(PosProduct::getName)
                    .distinct()
                    .collect(Collectors.toList());
        }

        final List<String> finalNames = productNames;
        List<Object[]> raw = (finalNames == null || finalNames.isEmpty())
                ? orderRepo.findHeatmapDataByMinute(storeId, fromTs, toTs)
                : orderRepo.findHeatmapDataByMinuteAndProductNames(storeId, fromTs, toTs, finalNames);

        Map<LocalDate, Integer> ordersByDate = new LinkedHashMap<>();
        for (Object[] row : raw) {
            LocalDate ld = LocalDate.parse(row[0].toString());
            if (ld.isAfter(today) || ld.isBefore(fromDate)) continue;
            int cnt = ((Number) row[2]).intValue();
            ordersByDate.merge(ld, cnt, Integer::sum);
        }

        Map<Integer, Set<LocalDate>> dowDates = new HashMap<>();
        for (LocalDate ld : ordersByDate.keySet()) {
            int dow = ld.getDayOfWeek().getValue();
            dowDates.computeIfAbsent(dow, k -> new HashSet<>()).add(ld);
        }

        Map<String, double[]> rawSum = new LinkedHashMap<>();
        for (Object[] row : raw) {
            LocalDate ld = LocalDate.parse(row[0].toString());
            if (ld.isAfter(today) || ld.isBefore(fromDate)) continue;
            int minute = ((Number) row[1]).intValue();
            if (minute < START_MIN || minute >= END_MIN) continue;
            int    cnt = ((Number) row[2]).intValue();
            double rev = ((Number) row[3]).doubleValue();
            int dow     = ld.getDayOfWeek().getValue();
            int slotIdx = (minute - START_MIN) / periodMinutes;
            String key  = dow + "_" + slotIdx;
            rawSum.computeIfAbsent(key, k -> new double[]{0, 0});
            rawSum.get(key)[0] += cnt;
            rawSum.get(key)[1] += rev;
        }

        Map<String, double[]> avgMap = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : rawSum.entrySet()) {
            String key = e.getKey();
            int dow = Integer.parseInt(key.split("_")[0]);
            int occ = dowDates.getOrDefault(dow, Set.of()).size();
            if (occ == 0) continue;
            avgMap.put(key, new double[]{
                    e.getValue()[0] / occ,
                    e.getValue()[1] / occ
            });
        }

        double maxCell = avgMap.values().stream()
                .mapToDouble(v -> v[0]).max().orElse(1.0);
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
                int slotEndMin   = slotStartMin + periodMinutes;
                String hStart    = String.format("%02d:%02d",
                        slotStartMin / 60, slotStartMin % 60);
                String hEnd      = String.format("%02d:%02d",
                        slotEndMin / 60, slotEndMin % 60);
                String hourLabel = hStart + "-" + hEnd;
                String   key = dow + "_" + si;
                double[] avg = avgMap.getOrDefault(key, new double[]{0, 0});
                result.add(new PosChartDto.HeatmapCell(
                        "DOW_" + dow, dowNames[dow],
                        slotStartMin, hourLabel,
                        avg[0], avg[1],
                        hasData, step
                ));
            }
        }
        return result;
    }

    public Long resolveStoreId(Long userId, Long requestedStoreId) {
        if (requestedStoreId != null) return requestedStoreId;
        return userStoreRepo.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Chưa gán store"))
                .getStore().getId();
    }
}