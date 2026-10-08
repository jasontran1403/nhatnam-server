package com.nhatnam.server.service;

import com.nhatnam.server.dto.RevenueIntelligenceDto;
import com.nhatnam.server.dto.RevenueIntelligenceDto.*;
import com.nhatnam.server.repository.pos.PosOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Log4j2
public class RevenueIntelligenceService {

    private final PosOrderRepository orderRepo;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Tính Revenue Intelligence cho khoảng hiển thị [displayFrom, displayTo].
     *
     * Walk-forward: Expected ngày t chỉ dùng dữ liệu ≤ t-1.
     * Backend tự kéo thêm longWindow ngày trước displayFrom để có đủ history.
     */
    public Response compute(Long storeId, long displayFromTs, long displayToTs,
                            Config config) {
        if (config == null) config = Config.builder().build();

        final int shortW  = config.getShortWindow();
        final int longW   = config.getLongWindow();
        final double alpha = config.getAlpha();
        final double normalTh = config.getNormalThreshold();
        final double alertTh  = config.getAlertThreshold();

        // ── 1. Xác định khoảng cần query (mở rộng thêm longWindow ngày) ──
        LocalDate displayFrom = Instant.ofEpochMilli(displayFromTs).atZone(VN).toLocalDate();
        LocalDate displayTo   = Instant.ofEpochMilli(displayToTs).atZone(VN).toLocalDate();
        LocalDate today       = LocalDate.now(VN);
        if (displayTo.isAfter(today)) displayTo = today;

        LocalDate historyFrom = displayFrom.minusDays(longW);

        long queryFromTs = historyFrom.atStartOfDay(VN).toInstant().toEpochMilli();
        long queryToTs   = displayTo.plusDays(1).atStartOfDay(VN).toInstant().toEpochMilli() - 1;

        // ── 2. Query doanh thu theo ngày ──────────────────────────────────
        List<Object[]> raw = orderRepo.findDailyRevenue(storeId, queryFromTs, queryToTs);

        // Chuyển thành Map<LocalDate, Double>
        Map<LocalDate, Double> revenueMap = new LinkedHashMap<>();
        for (Object[] row : raw) {
            LocalDate date = LocalDate.parse(row[0].toString());
            double revenue = ((Number) row[1]).doubleValue();
            revenueMap.put(date, revenue);
        }

        // Fill ngày thiếu = 0
        for (LocalDate d = historyFrom; !d.isAfter(displayTo); d = d.plusDays(1)) {
            revenueMap.putIfAbsent(d, 0.0);
        }

        // Sorted list
        List<LocalDate> allDates = new ArrayList<>(revenueMap.keySet());
        Collections.sort(allDates);

        // ── 3. Walk-forward tính Expected cho mỗi ngày hiển thị ──────────
        List<DaySnapshot> snapshots = new ArrayList<>();
        int consecutiveAbnormal = 0;
        String abnormalDir = null;

        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("dd/MM");

        for (LocalDate d = displayFrom; !d.isAfter(displayTo); d = d.plusDays(1)) {
            double actual = revenueMap.getOrDefault(d, 0.0);
            boolean isPartial = d.isEqual(today); // ← ngày hôm nay = chưa chốt

            // MA7: trung bình 7 ngày hoàn tất trước d (d-7 .. d-1)
            double ma7 = calcMA(revenueMap, d, shortW);
            // MA28: trung bình 28 ngày hoàn tất trước d (d-28 .. d-1)
            double ma28 = calcMA(revenueMap, d, longW);

            double expected = alpha * ma7 + (1 - alpha) * ma28;

            double lower15 = expected * (1 - alertTh);
            double lower5  = expected * (1 - normalTh);
            double upper5  = expected * (1 + normalTh);
            double upper15 = expected * (1 + alertTh);

            // ── Partial day: KHÔNG tính deviation & classification ────────
            // Vì actual chưa đầy đủ (ngày chưa kết thúc), so sánh với
            // expected cả ngày sẽ tạo deviation giả (luôn âm nặng).
            // Expected vẫn được tính bình thường vì nó chỉ dùng history.
            double deviation;
            String classification;

            if (isPartial) {
                deviation = 0.0;
                classification = "PARTIAL";
            } else {
                deviation = expected > 0
                        ? ((actual - expected) / expected) * 100.0
                        : 0.0;
                classification = classify(deviation, normalTh * 100, alertTh * 100);
            }

            // Persistence — chỉ tính cho ngày đã chốt
            if (!isPartial) {
                boolean isAbnormal = !classification.equals("NORMAL");
                boolean isPositive = deviation > 0;

                if (isAbnormal) {
                    String dir = isPositive ? "POSITIVE" : "NEGATIVE";
                    if (abnormalDir != null && abnormalDir.equals(dir)) {
                        consecutiveAbnormal++;
                    } else {
                        consecutiveAbnormal = 1;
                        abnormalDir = dir;
                    }
                } else {
                    consecutiveAbnormal = 0;
                    abnormalDir = null;
                }
            }

            snapshots.add(DaySnapshot.builder()
                    .date(d.toString())
                    .dateLabel(d.format(labelFmt))
                    .actualRevenue(actual)
                    .ma7(round2(ma7))
                    .ma28(round2(ma28))
                    .alpha(alpha)
                    .expectedRevenue(round2(expected))
                    .lower15(round2(lower15))
                    .lower5(round2(lower5))
                    .upper5(round2(upper5))
                    .upper15(round2(upper15))
                    .deviationPct(round2(deviation))
                    .classification(classification)
                    .consecutiveAbnormalDays(isPartial ? 0 : consecutiveAbnormal)
                    .abnormalDirection(isPartial ? null : abnormalDir)
                    .modelVersion(config.getModelVersion())
                    .partial(isPartial)
                    .build());
        }

        // ── 4. Summary stats — chỉ đếm ngày đã chốt ────────────────────
        long completedCount = snapshots.stream().filter(s -> !s.isPartial()).count();
        int total   = (int) completedCount;
        int normal  = (int) snapshots.stream().filter(s -> !s.isPartial() && "NORMAL".equals(s.getClassification())).count();
        int alertLo = (int) snapshots.stream().filter(s -> !s.isPartial() && "ALERT_LOW".equals(s.getClassification())).count();
        int strong  = (int) snapshots.stream().filter(s -> !s.isPartial() && "STRONG_OUTPERFORMANCE".equals(s.getClassification())).count();
        int belowExp = (int) snapshots.stream().filter(s -> !s.isPartial() && "BELOW_EXPECTED".equals(s.getClassification())).count();
        int aboveExp = (int) snapshots.stream().filter(s -> !s.isPartial() && "ABOVE_EXPECTED".equals(s.getClassification())).count();

        return Response.builder()
                .config(config)
                .snapshots(snapshots)
                .totalDays(total)
                .normalDays(normal)
                .belowExpectedDays(belowExp)
                .aboveExpectedDays(aboveExp)
                .alertLowDays(alertLo)
                .strongOutperformanceDays(strong)
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private double calcMA(Map<LocalDate, Double> map, LocalDate d, int window) {
        double sum = 0;
        int count = 0;
        for (int i = 1; i <= window; i++) {
            LocalDate past = d.minusDays(i);
            Double rev = map.get(past);
            if (rev != null) {
                sum += rev;
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    private String classify(double deviationPct, double normalPct, double alertPct) {
        if (deviationPct < -alertPct)       return "ALERT_LOW";
        if (deviationPct < -normalPct)      return "BELOW_EXPECTED";
        if (deviationPct <= normalPct)       return "NORMAL";
        if (deviationPct <= alertPct)        return "ABOVE_EXPECTED";
        return "STRONG_OUTPERFORMANCE";
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}