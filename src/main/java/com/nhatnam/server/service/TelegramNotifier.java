package com.nhatnam.server.service;

import com.nhatnam.server.config.TelegramProperties;
import com.nhatnam.server.dto.TelegramDto.LinkInitResponse;
import com.nhatnam.server.dto.TelegramDto.LinkStatus;
import com.nhatnam.server.entity.TelegramLink;
import com.nhatnam.server.entity.TelegramLinkToken;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.TelegramLinkRepository;
import com.nhatnam.server.repository.TelegramLinkTokenRepository;
import com.nhatnam.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Trung tâm gửi noti Telegram cho eOffice task.
 *
 *   • notifyUser(userId, text)     — DM riêng cho 1 user nếu đã liên kết
 *   • notifyAdmins(text)           — DM cho tất cả ADMIN + SUPERADMIN đã liên kết
 *
 * Tất cả gọi đều bất đồng bộ (fire-and-forget) — không được block flow chính
 * của TaskService khi Telegram chậm/lỗi mạng.
 *
 * Link flow:
 *   • createLinkToken(user)                     → sinh mã, trả về deep link
 *   • confirmLink(token, chatId, username, dn)  → được TelegramPollingScheduler gọi
 *   • unlink(userId)
 *
 * Không đụng gì tới TelegramService cũ (utils/) đang gửi hóa đơn vào group.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class TelegramNotifier {

    private final TelegramProperties props;
    private final TelegramLinkRepository linkRepo;
    private final TelegramLinkTokenRepository tokenRepo;
    private final UserRepository userRepo;

    private final RestTemplate rest = new RestTemplate();
    private final SecureRandom rng = new SecureRandom();

    /** Pool nhỏ cho gửi noti — không block caller (TaskService) */
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "tg-notify");
        t.setDaemon(true);
        return t;
    });

    // ─── Public API cho TaskService ─────────────────────────────

    /** Gửi noti cho 1 user (bất đồng bộ). Nếu user chưa link Telegram → bỏ qua yên lặng. */
    public void notifyUser(Long userId, String text) {
        if (!props.isReady() || userId == null || text == null) return;
        pool.submit(() -> {
            try {
                linkRepo.findByUserId(userId).ifPresent(l -> sendMessage(l.getChatId(), text));
            } catch (Exception e) {
                log.warn("[TG] notifyUser {} failed: {}", userId, e.getMessage());
            }
        });
    }

    /** Gửi noti cho toàn bộ ADMIN + SUPERADMIN đã liên kết. */
    public void notifyAdmins(String text) {
        if (!props.isReady() || text == null) return;
        pool.submit(() -> {
            try {
                List<User> admins = userRepo.findAll().stream()
                        .filter(u -> u.getRole() == Role.ADMIN || u.getRole() == Role.SUPERADMIN)
                        .toList();
                if (admins.isEmpty()) return;
                List<Long> ids = admins.stream().map(u -> u.id).toList();
                for (TelegramLink l : linkRepo.findByUserIdIn(ids)) {
                    sendMessage(l.getChatId(), text);
                }
            } catch (Exception e) {
                log.warn("[TG] notifyAdmins failed: {}", e.getMessage());
            }
        });
    }

    // ─── Link flow ──────────────────────────────────────────────

    /**
     * Bắt đầu flow liên kết: sinh mã 1 lần, trả về deep link để FE mở Telegram.
     * Nếu user đã có mã cũ còn hiệu lực → xóa và cấp mã mới (đơn giản hóa UX).
     */
    @Transactional
    public LinkInitResponse createLinkToken(User user) {
        if (!props.isReady()) throw new IllegalStateException("Telegram bot chưa được cấu hình");

        tokenRepo.deleteByUserId(user.getId());

        String token = randomToken();
        long now = System.currentTimeMillis();
        long ttlMs = props.getLink().getTtlMinutes() * 60_000L;
        tokenRepo.save(TelegramLinkToken.builder()
                .token(token).userId(user.getId())
                .createdAt(now).expiresAt(now + ttlMs)
                .build());

        return LinkInitResponse.builder()
                .token(token)
                .telegramDeepLink(props.deepLink(token))
                .botUsername(props.getBot().getUsername())
                .expiresAt(now + ttlMs)
                .build();
    }

    /** Trạng thái liên kết hiện tại của user. */
    public LinkStatus getStatus(User user) {
        boolean ready = props.isReady();
        Optional<TelegramLink> linkOpt = linkRepo.findByUserId(user.getId());
        if (linkOpt.isEmpty()) {
            return LinkStatus.builder().linked(false).botConfigured(ready).build();
        }
        TelegramLink l = linkOpt.get();
        return LinkStatus.builder()
                .linked(true).botConfigured(ready)
                .telegramUsername(l.getTelegramUsername())
                .telegramDisplayName(l.getTelegramDisplayName())
                .linkedAt(l.getLinkedAt())
                .build();
    }

    /** User tự hủy liên kết từ FE. */
    @Transactional
    public void unlink(User user) {
        linkRepo.deleteByUserId(user.getId());
        tokenRepo.deleteByUserId(user.getId());
    }

    /**
     * Được TelegramPollingScheduler gọi khi bot nhận /start &lt;token&gt;.
     * Xác thực token, gắn chat_id, xóa token đã dùng.
     *
     * @return tên user eOffice nếu link OK; null nếu token sai/hết hạn
     */
    @Transactional
    public String confirmLink(String token, long chatId, String tgUsername, String tgDisplayName) {
        Optional<TelegramLinkToken> tOpt = tokenRepo.findByToken(token);
        if (tOpt.isEmpty()) return null;
        TelegramLinkToken t = tOpt.get();
        if (t.getExpiresAt() < System.currentTimeMillis()) {
            tokenRepo.delete(t);
            return null;
        }
        User user = userRepo.findById(t.getUserId()).orElse(null);
        if (user == null) { tokenRepo.delete(t); return null; }

        // Nếu chat_id này đã link với user khác → tháo liên kết cũ (Telegram account
        // được đổi chủ). Nếu user này đã link chat_id khác → thay bằng chat_id mới.
        linkRepo.findByChatId(chatId).ifPresent(existing -> {
            if (!existing.getUserId().equals(user.getId())) linkRepo.delete(existing);
        });
        TelegramLink link = linkRepo.findByUserId(user.getId()).orElseGet(TelegramLink::new);
        link.setUserId(user.getId());
        link.setChatId(chatId);
        link.setTelegramUsername(tgUsername);
        link.setTelegramDisplayName(tgDisplayName);
        link.setLinkedAt(System.currentTimeMillis());
        linkRepo.save(link);

        tokenRepo.delete(t);
        return user.getFullName() != null ? user.getFullName() : user.getUsername();
    }

    // ─── HTTP với Telegram Bot API ──────────────────────────────

    /** Gửi tin nhắn text (HTML). PUBLIC vì TelegramPollingScheduler cũng cần reply lại /start. */
    public void sendMessage(long chatId, String htmlText) {
        if (!props.isReady()) return;
        String url = props.botApiBase() + "/sendMessage";
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", htmlText);
        payload.put("parse_mode", "HTML");
        payload.put("disable_web_page_preview", true);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        try {
            rest.postForObject(url, new HttpEntity<>(payload, h), String.class);
        } catch (Exception e) {
            log.warn("[TG] sendMessage chat={} failed: {}", chatId, e.getMessage());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private String randomToken() {
        // 15 ký tự base62 — đủ ngẫu nhiên, ngắn, không đụng ký tự Telegram parse start
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(15);
        for (int i = 0; i < 15; i++) sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        return sb.toString();
    }

    /** Escape để dùng trong parse_mode=HTML. Dùng cho content động (title, tên user...). */
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
