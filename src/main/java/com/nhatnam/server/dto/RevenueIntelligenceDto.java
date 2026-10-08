package com.nhatnam.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class RevenueIntelligenceDto {

    /**
     * Cấu hình V1 — client gửi lên hoặc dùng default.
     */
    @Data @Builder
    public static class Config {
        @Builder.Default private int    shortWindow      = 7;
        @Builder.Default private int    longWindow       = 28;
        @Builder.Default private double alpha            = 0.5;
        @Builder.Default private double normalThreshold  = 0.10;
        @Builder.Default private double alertThreshold   = 0.20;
        @Builder.Default private String modelVersion     = "RI_BASELINE_V1";
    }

    /**
     * Snapshot 1 ngày — đúng data model trong tài liệu (Section 14).
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DaySnapshot {
        private String  date;            // yyyy-MM-dd
        private String  dateLabel;       // dd/MM
        private double  actualRevenue;
        private double  ma7;
        private double  ma28;
        private double  alpha;
        private double  expectedRevenue;
        private double  lower15;
        private double  lower5;
        private double  upper5;
        private double  upper15;
        private double  deviationPct;    // (actual - expected) / expected × 100
        private String  classification;  // ALERT_LOW | BELOW_EXPECTED | NORMAL | ABOVE_EXPECTED | STRONG_OUTPERFORMANCE
        private int     consecutiveAbnormalDays;
        private String  abnormalDirection; // POSITIVE | NEGATIVE | null
        private String  modelVersion;
        private boolean partial;         // true = ngày chưa chốt (hôm nay), không nên dùng deviation/classification
    }

    /**
     * Response trả về cho client.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Response {
        private Config              config;
        private List<DaySnapshot>   snapshots;
        private int                 totalDays;
        private int                 normalDays;
        private int                 belowExpectedDays;      // ← thêm
        private int                 aboveExpectedDays;      // ← thêm
        private int                 alertLowDays;
        private int                 strongOutperformanceDays;
    }
}