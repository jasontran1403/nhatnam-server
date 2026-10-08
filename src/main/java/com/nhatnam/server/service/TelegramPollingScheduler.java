package com.nhatnam.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.config.TelegramProperties;
import com.nhatnam.server.repository.TelegramLinkTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Long-polling Telegram: mỗi vài giây gọi getUpdates để nhận /start từ user.
 *
 * Chọn long-polling vì eOffice FE deploy trên Vercel còn BE thường
 * chạy nội bộ / VPS riêng — không phải lúc nào cũng có domain HTTPS public
 * cho Telegram webhook POST về. Long-polling đi ra ngoài, không cần expose gì.
 *
 * Nếu sau này BE có domain HTTPS công khai và bạn muốn dùng webhook,
 * chỉ cần set telegram.polling.enabled=false rồi thêm 1 controller
 * xử lý POST /api/telegram/webhook — logic parse update giữ nguyên,
 * gọi cùng handleUpdate() bên dưới.
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class TelegramPollingScheduler {

    private final TelegramProperties props;
    private final TelegramNotifier notifier;
    private final TelegramLinkTokenRepository tokenRepo;

    /** RestTemplate riêng với timeout dài hơn interval để long-poll cho hiệu quả */
    private final RestTemplate rest = buildRest();
    private final ObjectMapper json = new ObjectMapper();

    /** update_id của lần cuối đã xử lý (+1). Trong bộ nhớ — mất khi restart cũng ok
     *  vì Telegram sẽ replay và các /start cũ đã có token đều hết hạn/đã dùng. */
    private final AtomicLong offset = new AtomicLong(0);

    /** true = đang có request getUpdates chạy, tránh chồng chéo */
    private volatile boolean inFlight = false;

    @Scheduled(fixedDelayString = "${telegram.polling.interval-ms:2000}")
    public void poll() {
        if (!props.isReady() || !props.getPolling().isEnabled()) return;
        if (inFlight) return;
        inFlight = true;
        try {
            String url = props.botApiBase() + "/getUpdates";
            Map<String, Object> body = new HashMap<>();
            body.put("timeout", props.getPolling().getTimeoutSec());
            body.put("offset", offset.get());
            body.put("allowed_updates", new String[]{"message"});

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);

            String resp;
            try {
                resp = rest.postForObject(url, new HttpEntity<>(body, h), String.class);
            } catch (Exception e) {
                log.debug("[TG] getUpdates network err: {}", e.getMessage());
                return;
            }
            if (resp == null) return;

            JsonNode root = json.readTree(resp);
            if (!root.path("ok").asBoolean(false)) {
                log.warn("[TG] getUpdates not ok: {}", root.path("description").asText());
                return;
            }
            for (JsonNode upd : root.path("result")) {
                long updateId = upd.path("update_id").asLong(0);
                offset.updateAndGet(cur -> Math.max(cur, updateId + 1));
                try { handleUpdate(upd); }
                catch (Exception e) { log.warn("[TG] handleUpdate err (update_id={}): {}", updateId, e.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("[TG] poll err: {}", e.getMessage());
        } finally {
            inFlight = false;
        }
    }

    /** Dọn token hết hạn mỗi 5 phút — tránh bảng phình vô hạn. */
    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    public void cleanupExpiredTokens() {
        try {
            int n = tokenRepo.deleteExpired(System.currentTimeMillis());
            if (n > 0) log.info("[TG] cleaned {} expired link tokens", n);
        } catch (Exception ignored) { /* first boot, table not created yet */ }
    }

    // ─── Xử lý 1 update ─────────────────────────────────────────

    private void handleUpdate(JsonNode upd) {
        JsonNode msg = upd.path("message");
        if (msg.isMissingNode()) return;
        String text = msg.path("text").asText("");
        long chatId = msg.path("chat").path("id").asLong(0);
        if (chatId == 0) return;

        // Chỉ xử lý chat riêng — không xử lý group/channel
        String chatType = msg.path("chat").path("type").asText("");
        if (!"private".equals(chatType)) return;

        String tgUsername    = msg.path("from").path("username").asText(null);
        String tgFirst       = msg.path("from").path("first_name").asText("");
        String tgLast        = msg.path("from").path("last_name").asText("");
        String tgDisplayName = (tgFirst + " " + tgLast).trim();

        if (text.startsWith("/start")) {
            String payload = text.length() > 6 ? text.substring(6).trim() : "";
            if (payload.isEmpty()) {
                notifier.sendMessage(chatId,
                        "👋 Chào bạn! Đây là bot noti của <b>eOffice</b>.\n\n" +
                        "Để liên kết, vui lòng vào eOffice → bấm nút <b>Liên kết Telegram</b>, " +
                        "rồi bấm nút mở Telegram trên đó (link sẽ tự kèm mã liên kết).");
                return;
            }
            String userName = notifier.confirmLink(payload, chatId, tgUsername, tgDisplayName);
            if (userName == null) {
                notifier.sendMessage(chatId,
                        "❌ Mã liên kết không hợp lệ hoặc đã hết hạn.\n" +
                        "Vui lòng vào eOffice bấm lại nút <b>Liên kết Telegram</b> để lấy mã mới.");
            } else {
                notifier.sendMessage(chatId,
                        "✅ Đã liên kết thành công với tài khoản eOffice: <b>" +
                        TelegramNotifier.esc(userName) + "</b>\n\n" +
                        "Từ giờ bạn sẽ nhận noti khi có task mới được giao, task cập nhật, " +
                        "hoặc yêu cầu gia hạn ngay tại đây.");
            }
        } else if ("/unlink".equalsIgnoreCase(text.trim())) {
            notifier.sendMessage(chatId,
                    "ℹ Để hủy liên kết, vui lòng vào eOffice → mục <b>Liên kết Telegram</b> → " +
                    "bấm <b>Hủy liên kết</b>. (Bot cần chắc chắn đúng chủ tài khoản eOffice thao tác.)");
        } else if ("/help".equalsIgnoreCase(text.trim()) || "/start".equalsIgnoreCase(text.trim())) {
            notifier.sendMessage(chatId,
                    "Bot noti eOffice.\n" +
                    "• /start &lt;mã&gt; — liên kết với tài khoản eOffice\n" +
                    "• /help — trợ giúp");
        }
        // Các tin nhắn khác — bỏ qua yên lặng
    }

    private static RestTemplate buildRest() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(35).toMillis()); // > polling timeout 25s
        return new RestTemplate(f);
    }
}
