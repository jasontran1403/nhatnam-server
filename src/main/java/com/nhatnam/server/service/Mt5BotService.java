package com.nhatnam.server.service;

import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Log4j2
public class Mt5BotService {

    private final Mt5BotAccountRepository accountRepo;
    private final Mt5BotOpenPositionRepository openRepo;
    private final Mt5BotClosedOrderRepository closedRepo;
    private final Mt5NewsEventRepository newsEventRepo;
    private final SimpMessagingTemplate ws;

    /** Passcode bắt buộc khi "tắt bot" (STOPPING). Có thể override qua application.yml. */
    @Value("${mt5bot.stop-passcode:160625}")
    private String stopPasscode;

    /**
     * News snapshot CHUNG cho mọi account (news là global, không gắn account).
     * Cứ account nào sync lên thì cập nhật — các account khác đọc chung.
     * Lưu in-memory (ephemeral) vì news phản ánh "hiện tại", restart lại là nhận lại ngay.
     */
    private volatile Map<String, Object> currentNews = null;
    private volatile LocalDateTime newsUpdatedAt = null;
    private volatile String newsSourceLogin = null;

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final DateTimeFormatter MT5_FMT =
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");

    // ================================================================
    // SYNC — MT5 gọi mỗi 1 giây.
    //
    // Request:
    //   {
    //     "login":"463936671", "server":"Exness-MT5Trial17", "name":"Standard",
    //     "balance": 1000.0, "equity": 998.3,
    //     "openPositions":[{ticket, symbol, direction, volume, openPrice, currentPrice, profit, swap, openTime}],
    //     "closedOrders":[{ticket, symbol, direction, volume, openPrice, closePrice, profit, commission, swap, fee, openTime, closeTime, comment}]
    //   }
    //
    // Response:
    //   { "lot": 0.01, "state":"RUNNING", "latestClosedTicket": 123123123 }
    // ================================================================
    @Transactional
    public Map<String, Object> sync(Map<String, Object> req) {
        String login  = reqString(req, "login");
        String server = reqString(req, "server");
        String name   = optString(req, "name");
        if (login == null || server == null) {
            throw new IllegalArgumentException("login & server are required");
        }

        Mt5BotAccountEntity acc = accountRepo.findByLoginAndServer(login, server)
                .orElseGet(() -> Mt5BotAccountEntity.builder()
                        .login(login).server(server).name(name)
                        .lot(0.0).state(Mt5BotState.PAUSED)
                        .latestClosedTicket(0L)
                        .build());

        boolean isNew = acc.getId() == null;
        if (name != null && !name.isBlank()) acc.setName(name);

        acc.setBalance(toDouble(req.get("balance")));
        acc.setEquity(toDouble(req.get("equity")));
        acc.setLastSyncAt(LocalDateTime.now());

        // ---- AVOIDING_NEWS flag (song song với state) ----
        // Payload mong đợi:
        //   "avoidingNews": { "active": true, "title": "USD CPI", "until": "2026.10.07 15:40:00" }
        //   hoặc "avoidingNews": { "active": false }  (hoặc bỏ qua)
        //   hoặc "avoidingNews": false (shortcut tắt cờ)
        Object avObj = req.get("avoidingNews");
        if (avObj instanceof Map<?, ?> avMapRaw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> avMap = (Map<String, Object>) avMapRaw;
            Object actRaw = avMap.get("active");
            boolean active = actRaw instanceof Boolean b ? b
                    : (actRaw != null && Boolean.parseBoolean(String.valueOf(actRaw)));
            if (active) {
                acc.setAvoidingNews(true);
                acc.setAvoidingNewsTitle(optString(avMap, "title"));
                acc.setAvoidingNewsUntil(parseTime(avMap.get("until")));
            } else {
                acc.setAvoidingNews(false);
                acc.setAvoidingNewsTitle(null);
                acc.setAvoidingNewsUntil(null);
            }
        } else if (avObj instanceof Boolean b && !b) {
            acc.setAvoidingNews(false);
            acc.setAvoidingNewsTitle(null);
            acc.setAvoidingNewsUntil(null);
        }

        // Auto-expire: nếu until đã qua thì tự tắt cờ (phòng khi bot miss 1 sync)
        // So sánh ở UTC vì tất cả thời gian news/avoiding_until đều lưu UTC
        // (MT5 Calendar trả về UTC → _BS_FmtTime serialize naive → BE parse là UTC).
        if (Boolean.TRUE.equals(acc.getAvoidingNews())
                && acc.getAvoidingNewsUntil() != null
                && !acc.getAvoidingNewsUntil().isAfter(LocalDateTime.now(UTC))) {
            acc.setAvoidingNews(false);
            acc.setAvoidingNewsTitle(null);
            acc.setAvoidingNewsUntil(null);
        }

        // News SHARED: nhận bất kỳ account nào gửi lên.
        //   - Config + currentBlock lưu singleton (in-memory, phản ánh "hiện tại")
        //   - upcoming[] lưu DB, dedupe theo (name, event_time), persistent
        Object newsObj = req.get("news");
        if (newsObj instanceof Map<?, ?> newsMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) newsMap;
            currentNews     = casted;
            newsUpdatedAt   = LocalDateTime.now();
            newsSourceLogin = login;

            // Persist upcoming events (chỉ tin mới chưa có trong DB)
            persistNewsEvents(casted);
            // Cũng persist currentBlock nếu có (tin "đang chặn" cũng là 1 event hợp lệ)
            Object cbObj = casted.get("currentBlock");
            if (cbObj instanceof Map<?, ?> cbMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cb = (Map<String, Object>) cbMap;
                persistOneNewsEvent(
                        (String) cb.get("name"),
                        parseTime(cb.get("time")),
                        cb.get("importance"),
                        null   // currentBlock không có currency, mặc định USD trong EA
                );
            }

            broadcastNews();
        }

        // ---- Replace open positions snapshot ----
        List<Map<String, Object>> openList = toListOfMap(req.get("openPositions"));
        // persist account first (need id)
        acc = accountRepo.save(acc);
        openRepo.deleteAllByAccountId(acc.getId());

        double totalOpenLot = 0.0, totalOpenProfit = 0.0;
        List<Mt5BotOpenPositionEntity> newOpen = new ArrayList<>(openList.size());
        for (Map<String, Object> p : openList) {
            Long ticket = toLong(p.get("ticket"));
            if (ticket == null) continue;
            double vol = defDouble(p.get("volume"));
            double pr  = defDouble(p.get("profit"));
            totalOpenLot    += vol;
            totalOpenProfit += pr;
            newOpen.add(Mt5BotOpenPositionEntity.builder()
                    .accountId(acc.getId())
                    .ticket(ticket)
                    .symbol(optString(p, "symbol"))
                    .direction(optString(p, "direction"))
                    .volume(vol)
                    .openPrice(toDouble(p.get("openPrice")))
                    .currentPrice(toDouble(p.get("currentPrice")))
                    .profit(pr)
                    .swap(toDouble(p.get("swap")))
                    .openTime(parseTime(p.get("openTime")))
                    .build());
        }
        openRepo.saveAll(newOpen);
        acc.setTotalOpenLot(totalOpenLot);
        acc.setTotalOpenProfit(totalOpenProfit);

        // ---- Append new closed orders ----
        // Chống duplicate ở 2 lớp:
        //   1) Set seenInPayload — bỏ qua cùng ticket xuất hiện nhiều lần
        //      trong CÙNG payload (xảy ra khi 1 position có nhiều deal OUT
        //      do partial close).
        //   2) existsByAccountIdAndTicket — bỏ qua ticket đã có trong DB
        //      từ các sync trước (xảy ra khi EA re-attach, g_bot_latest_tk
        //      reset về 0, scan lại 365 ngày).
        List<Map<String, Object>> closedList = toListOfMap(req.get("closedOrders"));
        long newestTicketSeen = acc.getLatestClosedTicket();
        List<Mt5BotClosedOrderEntity> toSave = new ArrayList<>();
        java.util.Set<Long> seenInPayload = new java.util.HashSet<>();
        for (Map<String, Object> c : closedList) {
            Long ticket = toLong(c.get("ticket"));
            if (ticket == null) continue;
            if (ticket <= acc.getLatestClosedTicket()) continue;
            if (!seenInPayload.add(ticket)) continue;                         // duplicate trong payload
            if (closedRepo.existsByAccountIdAndTicket(acc.getId(), ticket)) {
                // Đã có trong DB → coi như đã nhận, update latestTicket
                if (ticket > newestTicketSeen) newestTicketSeen = ticket;
                continue;
            }

            toSave.add(Mt5BotClosedOrderEntity.builder()
                    .accountId(acc.getId())
                    .ticket(ticket)
                    .symbol(optString(c, "symbol"))
                    .direction(optString(c, "direction"))
                    .volume(toDouble(c.get("volume")))
                    .openPrice(toDouble(c.get("openPrice")))
                    .closePrice(toDouble(c.get("closePrice")))
                    .profit(toDouble(c.get("profit")))
                    .commission(toDouble(c.get("commission")))
                    .swap(toDouble(c.get("swap")))
                    .fee(toDouble(c.get("fee")))
                    .openTime(parseTime(c.get("openTime")))
                    .closeTime(parseTime(c.get("closeTime")))
                    .comment(optString(c, "comment"))
                    .build());
            if (ticket > newestTicketSeen) newestTicketSeen = ticket;
        }
        if (!toSave.isEmpty()) {
            try {
                closedRepo.saveAll(toSave);
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                // Backup: nếu vẫn race condition, fallback save từng cái bỏ qua duplicate
                log.warn("[MT5-BOT] Batch save conflict ({}), falling back to per-row save", dup.getMessage());
                for (Mt5BotClosedOrderEntity row : toSave) {
                    try {
                        if (!closedRepo.existsByAccountIdAndTicket(row.getAccountId(), row.getTicket()))
                            closedRepo.save(row);
                    } catch (Exception ex) {
                        log.debug("Skip dup ticket {}: {}", row.getTicket(), ex.getMessage());
                    }
                }
            }
        }
        if (newestTicketSeen > acc.getLatestClosedTicket())
            acc.setLatestClosedTicket(newestTicketSeen);

        acc = accountRepo.save(acc);

        // ---- Broadcast realtime ----
        broadcastAccount(acc);
        if (isNew) broadcastAccountList();

        // ---- Response: 3 field bot cần ----
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("lot", acc.getLot() == null ? 0.0 : acc.getLot());
        res.put("state", acc.getState().name());
        res.put("latestClosedTicket", acc.getLatestClosedTicket());
        return res;
    }

    // ================================================================
    // FE queries
    // ================================================================

    public List<Map<String, Object>> listAccounts() {
        return accountRepo.findAllByOrderByCreatedAtAsc()
                .stream().map(this::accountSummary).toList();
    }

    public Map<String, Object> getAccountDetail(Long accountId) {
        Mt5BotAccountEntity acc = accountRepo.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        Map<String, Object> m = accountSummary(acc);
        m.put("openPositions", openRepo.findAllByAccountIdOrderByOpenTimeAsc(acc.getId())
                .stream().map(this::openMap).toList());
        return m;
    }

    public Map<String, Object> getHistory(Long accountId, LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end   = to.plusDays(1).atStartOfDay();
        List<Mt5BotClosedOrderEntity> rows =
                closedRepo.findByAccountIdAndCloseTimeBetweenOrderByCloseTimeDesc(accountId, start, end);
        double totalLot = 0, totalProfit = 0;
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Mt5BotClosedOrderEntity r : rows) {
            totalLot    += defDouble(r.getVolume());
            double net   = defDouble(r.getProfit()) + defDouble(r.getCommission()) + defDouble(r.getSwap()) + defDouble(r.getFee());
            totalProfit += net;
            items.add(closedMap(r));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("items", items);
        res.put("totalLot", totalLot);
        res.put("totalProfit", totalProfit);
        return res;
    }

    // ================================================================
    // FE commands
    // ================================================================

    /** Đổi lot — chỉ cấm khi RUNNING. PAUSED hoặc STOPPING đều cho sửa. */
    @Transactional
    public Map<String, Object> setLot(Long accountId, double lot) {
        if (lot < 0) throw new IllegalArgumentException("lot must be >= 0");
        Mt5BotAccountEntity acc = accountRepo.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (acc.getState() == Mt5BotState.RUNNING) {
            throw new IllegalStateException("Bot đang CHẠY — hãy ngưng hoặc tắt bot trước khi sửa lot.");
        }
        acc.setLot(lot);
        acc = accountRepo.save(acc);
        broadcastAccount(acc);
        return accountSummary(acc);
    }

    /** PAUSED hoặc RUNNING. STOPPING đi qua stopBot() vì cần passcode. */
    @Transactional
    public Map<String, Object> setState(Long accountId, Mt5BotState next) {
        if (next == Mt5BotState.STOPPING)
            throw new IllegalArgumentException("Dùng endpoint /stop với passcode để chuyển sang STOPPING");

        Mt5BotAccountEntity acc = accountRepo.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        if (next == Mt5BotState.RUNNING && (acc.getLot() == null || acc.getLot() <= 0.0)) {
            throw new IllegalStateException("Chưa cấu hình lot (lot = 0). Hãy sửa lot trước khi bật.");
        }
        acc.setState(next);
        acc = accountRepo.save(acc);
        broadcastAccount(acc);
        return accountSummary(acc);
    }

    /** Tắt bot = STOPPING. Yêu cầu passcode. */
    @Transactional
    public Map<String, Object> stopBot(Long accountId, String passcode) {
        if (passcode == null || !passcode.equals(stopPasscode))
            throw new SecurityException("Passcode không đúng");
        Mt5BotAccountEntity acc = accountRepo.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        acc.setState(Mt5BotState.STOPPING);
        acc = accountRepo.save(acc);
        broadcastAccount(acc);
        return accountSummary(acc);
    }

    // ================================================================
    // Broadcast helpers
    // ================================================================

    private void broadcastAccount(Mt5BotAccountEntity acc) {
        Map<String, Object> payload = accountSummary(acc);
        payload.put("openPositions", openRepo.findAllByAccountIdOrderByOpenTimeAsc(acc.getId())
                .stream().map(this::openMap).toList());
        ws.convertAndSend("/topic/mt5-bot/" + acc.getId(), payload);
    }

    private void broadcastAccountList() {
        ws.convertAndSend("/topic/mt5-bot/accounts", listAccounts());
    }

    // ================================================================
    // Mapping helpers
    // ================================================================

    private Map<String, Object> accountSummary(Mt5BotAccountEntity acc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", acc.getId());
        m.put("login", acc.getLogin());
        m.put("server", acc.getServer());
        m.put("name", acc.getName());
        m.put("lot", acc.getLot());
        m.put("state", acc.getState().name());
        m.put("latestClosedTicket", acc.getLatestClosedTicket());
        m.put("balance", acc.getBalance());
        m.put("equity", acc.getEquity());
        m.put("totalOpenLot", acc.getTotalOpenLot());
        m.put("totalOpenProfit", acc.getTotalOpenProfit());
        m.put("lastSyncAt", acc.getLastSyncAt() == null ? null : acc.getLastSyncAt().toString());
        // AVOIDING_NEWS — flag phụ cho UI tham khảo
        m.put("avoidingNews", Boolean.TRUE.equals(acc.getAvoidingNews()));
        m.put("avoidingNewsTitle", acc.getAvoidingNewsTitle());
        m.put("avoidingNewsUntil", acc.getAvoidingNewsUntil() == null ? null : acc.getAvoidingNewsUntil().toString());
        return m;
    }

    /** News snapshot SHARED: config + currentBlock từ singleton, upcoming từ DB. */
    public Map<String, Object> getCurrentNews() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);

        Map<String, Object> newsOut;
        if (currentNews != null) {
            // Clone singleton và thay upcoming bằng list từ DB (persistent)
            newsOut = new LinkedHashMap<>(currentNews);
        } else {
            newsOut = null;
        }

        // Upcoming từ DB: event_time >= now, order asc.
        // Phải so UTC vì event_time lưu từ MT5 CalendarValueHistory là UTC.
        // Nếu dùng LocalDateTime.now() (giờ JVM) mà server chạy ở VN (UTC+7),
        // các tin trong khoảng 00:00–07:00 UTC đêm nay sẽ bị cắt sai.
        List<Map<String, Object>> dbUpcoming = newsEventRepo
                .findByEventTimeGreaterThanEqualOrderByEventTimeAsc(LocalDateTime.now(UTC))
                .stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getName());
                    m.put("time", e.getEventTime().toString());
                    m.put("importance", e.getImportance());
                    m.put("currency", e.getCurrency());
                    return m;
                }).toList();

        if (newsOut != null) {
            newsOut.put("upcoming", dbUpcoming);
        } else if (!dbUpcoming.isEmpty()) {
            // Có upcoming trong DB nhưng chưa có snapshot — vẫn trả ra để UI hiện
            newsOut = new LinkedHashMap<>();
            newsOut.put("upcoming", dbUpcoming);
        }

        res.put("news", newsOut);
        res.put("updatedAt", newsUpdatedAt == null ? null : newsUpdatedAt.toString());
        res.put("sourceLogin", newsSourceLogin);
        return res;
    }

    private void broadcastNews() {
        ws.convertAndSend("/topic/mt5-bot/news", getCurrentNews());
    }

    /** Xóa singleton + xóa toàn bộ news events trong DB. */
    @Transactional
    public void clearNews() {
        currentNews     = null;
        newsUpdatedAt   = null;
        newsSourceLogin = null;
        newsEventRepo.deleteAll();
        broadcastNews();
    }

    /** Lưu các event trong upcoming[], dedupe theo (name, event_time). */
    private void persistNewsEvents(Map<String, Object> newsMap) {
        Object up = newsMap.get("upcoming");
        if (!(up instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> ev = (Map<String, Object>) m;
            persistOneNewsEvent(
                    (String) ev.get("name"),
                    parseTime(ev.get("time")),
                    ev.get("importance"),
                    (String) ev.get("currency")
            );
        }
    }

    private void persistOneNewsEvent(String name, LocalDateTime eventTime, Object importanceRaw, String currency) {
        if (name == null || name.isBlank() || eventTime == null) return;
        if (newsEventRepo.existsByNameAndEventTime(name, eventTime)) return;
        try {
            int importance = importanceRaw instanceof Number n ? n.intValue()
                    : (importanceRaw != null ? Integer.parseInt(String.valueOf(importanceRaw)) : 0);
            newsEventRepo.save(Mt5NewsEventEntity.builder()
                    .name(name)
                    .eventTime(eventTime)
                    .importance(importance)
                    .currency(currency == null ? "USD" : currency)
                    .build());
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Race: another sync inserted same (name, time) — ignore
            log.debug("News event dup skipped: {} at {}", name, eventTime);
        } catch (Exception e) {
            log.debug("News event save failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> openMap(Mt5BotOpenPositionEntity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticket", p.getTicket());
        m.put("symbol", p.getSymbol());
        m.put("direction", p.getDirection());
        m.put("volume", p.getVolume());
        m.put("openPrice", p.getOpenPrice());
        m.put("currentPrice", p.getCurrentPrice());
        m.put("profit", p.getProfit());
        m.put("swap", p.getSwap());
        m.put("openTime", p.getOpenTime() == null ? null : p.getOpenTime().toString());
        return m;
    }

    private Map<String, Object> closedMap(Mt5BotClosedOrderEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticket", c.getTicket());
        m.put("positionTicket", c.getTicket()); // FE tương thích
        m.put("symbol", c.getSymbol());
        m.put("direction", c.getDirection());
        m.put("volume", c.getVolume());
        m.put("openPrice", c.getOpenPrice());
        m.put("closePrice", c.getClosePrice());
        m.put("profit", c.getProfit());
        m.put("commission", c.getCommission());
        m.put("swap", c.getSwap());
        m.put("fee", c.getFee());
        m.put("openTime", c.getOpenTime() == null ? null : c.getOpenTime().toString());
        m.put("closeTime", c.getCloseTime() == null ? null : c.getCloseTime().toString());
        m.put("comment", c.getComment());
        return m;
    }

    // ================================================================
    // Type helpers
    // ================================================================

    private static String reqString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String optString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static double defDouble(Object v) {
        Double d = toDouble(v);
        return d == null ? 0.0 : d;
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toListOfMap(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>(l.size());
            for (Object item : l) if (item instanceof Map<?, ?> mm) out.add((Map<String, Object>) mm);
            return out;
        }
        return List.of();
    }

    /**
     * Chấp nhận:
     *   - "2026.10.06 09:10:00" (MT5 TimeToString)
     *   - ISO "2026-10-06T09:10:00" / "2026-10-06T09:10:00Z"
     *   - epoch seconds hoặc millis (Number)
     * Giữ nguyên giá trị thời gian (UTC server time), không đổi timezone.
     */
    private static LocalDateTime parseTime(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) {
            long raw = n.longValue();
            // >= 10^12 là ms
            long ms = raw > 10_000_000_000L ? raw : raw * 1000L;
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), UTC);
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "0".equals(s)) return null;
        try {
            if (s.contains(".") && s.contains(" ")) return LocalDateTime.parse(s, MT5_FMT);
            if (s.endsWith("Z")) return LocalDateTime.ofInstant(Instant.parse(s), UTC);
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception e) {
            log.debug("Could not parse time: {}", s);
            return null;
        }
    }
}