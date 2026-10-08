package com.nhatnam.server.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Gom DTO của nhóm tiện ích vào một file cho gọn. */
public class ToolsDto {

    /**
     * Vùng ký do frontend gửi lên.
     * x, y, w, h = phần trăm (0–100) so với kích thước trang PDF,
     * gốc tọa độ ở GÓC TRÊN-TRÁI (giống DOM), backend tự lật sang hệ PDF.
     */
    @Data
    public static class SignZone {
        private int    page;        // 0-based
        private double x;
        private double y;
        private double w;
        private double h;
        private int    signerIdx;
        private String signerName;
    }

    @Data
    public static class SignRequest {
        private List<SignZone> zones;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SignResponse {
        private boolean success;
        private String  message;
        private String  filename;
    }

    /**
     * Vị trí watermark do người dùng chọn trên UI.
     * x, y = phần trăm (0–100) của TÂM watermark so với khung ảnh/video.
     * scale = bề rộng watermark / bề rộng ảnh gốc (0–1).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WatermarkSettings {
        private double x        = 50;
        private double y        = 50;
        private double scale    = 0.28;
        private double rotation = 0;
        private double opacity  = 1.0;
    }
}
