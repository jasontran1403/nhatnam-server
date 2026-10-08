package com.nhatnam.server.dto;

import lombok.*;

public class TelegramDto {

    /** Trạng thái liên kết trả về FE */
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class LinkStatus {
        /** true = đã link, false = chưa */
        private boolean linked;
        /** Bot có được cấu hình hoạt động không. false = admin hệ thống chưa nhập token → FE nên hiển thị "Chưa cấu hình bot" */
        private boolean botConfigured;
        /** Chỉ có khi linked=true */
        private String telegramUsername;
        private String telegramDisplayName;
        private Long linkedAt;
    }

    /** Response khi bắt đầu flow liên kết */
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class LinkInitResponse {
        /** Mã token 1 lần, FE hiển thị để user copy nếu cần */
        private String token;
        /** Deep link mở thẳng Telegram + auto điền lệnh /start &lt;token&gt; */
        private String telegramDeepLink;
        /** Username của bot (để FE hiển thị "Bấm nút bên dưới để mở @BotUsername") */
        private String botUsername;
        /** Epoch millis, thời điểm mã hết hạn */
        private Long expiresAt;
    }
}
