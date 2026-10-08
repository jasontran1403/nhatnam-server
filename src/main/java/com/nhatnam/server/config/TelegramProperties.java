package com.nhatnam.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình bot Telegram dùng cho notification cá nhân (eOffice task).
 *
 * Đọc từ application.yml, block "telegram":
 *
 *   telegram:
 *     bot:
 *       token: "12345:ABC..."       # lấy từ @BotFather
 *       username: "YourBotUsername" # KHÔNG có dấu @, để build deep link t.me/&lt;username&gt;
 *     enabled: true                 # tắt = không gửi noti, không polling
 *     polling:
 *       enabled: true               # true = long-polling; false = webhook (chưa hỗ trợ)
 *       interval-ms: 2000
 *       timeout-sec: 25
 *     link:
 *       ttl-minutes: 10             # mã liên kết hết hạn sau bao nhiêu phút
 *
 * File này KHÔNG dính gì tới TelegramService gửi hóa đơn vào group hiện tại.
 */
@Component
@ConfigurationProperties(prefix = "telegram")
@Getter @Setter
public class TelegramProperties {

    private boolean enabled = false;
    private Bot bot = new Bot();
    private Polling polling = new Polling();
    private Link link = new Link();

    @Getter @Setter
    public static class Bot {
        private String token = "";
        private String username = "";
    }

    @Getter @Setter
    public static class Polling {
        private boolean enabled = true;
        private long intervalMs = 2000;
        private int timeoutSec = 25;
    }

    @Getter @Setter
    public static class Link {
        private int ttlMinutes = 10;
    }

    public boolean isReady() {
        return enabled && bot != null && bot.token != null && !bot.token.isBlank();
    }

    public String botApiBase() {
        return "https://api.telegram.org/bot" + bot.token;
    }

    public String deepLink(String startPayload) {
        return "https://t.me/" + bot.username + "?start=" + startPayload;
    }
}
