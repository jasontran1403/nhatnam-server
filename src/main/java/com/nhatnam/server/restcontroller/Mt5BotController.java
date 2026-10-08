package com.nhatnam.server.restcontroller;

import com.nhatnam.server.entity.Mt5BotState;
import com.nhatnam.server.service.Mt5BotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/public/mt5-bot")
@RequiredArgsConstructor
@Log4j2
public class Mt5BotController {

    private final Mt5BotService service;

    // ============================================================
    // MT5 side — chạy mỗi 1 giây
    // ============================================================

    /**
     * MT5 gọi mỗi 1s. Lần đầu có thể gửi payload rỗng (chỉ login/server/name)
     * để nhận lại lot/state/latestClosedTicket.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(service.sync(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(err(e));
        } catch (Exception e) {
            log.error("[MT5-BOT SYNC] error", e);
            return ResponseEntity.internalServerError().body(err(e));
        }
    }

    // ============================================================
    // FE side
    // ============================================================

    @GetMapping("/accounts")
    public ResponseEntity<?> listAccounts() {
        try {
            return ResponseEntity.ok(Map.of("success", true, "items", service.listAccounts()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e));
        }
    }

    /** News snapshot SHARED (chung cho mọi tài khoản). */
    @GetMapping("/news")
    public ResponseEntity<?> getNews() {
        try {
            return ResponseEntity.ok(service.getCurrentNews());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e));
        }
    }

    /** Xóa news snapshot (admin). Sau khi gọi, sync EA tiếp theo sẽ ghi đè lại. */
    @DeleteMapping("/news")
    public ResponseEntity<?> clearNews() {
        try {
            service.clearNews();
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e));
        }
    }

    @GetMapping("/accounts/{id}")
    public ResponseEntity<?> getAccount(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(Map.of("success", true, "account", service.getAccountDetail(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(e));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e));
        }
    }

    @GetMapping("/accounts/{id}/history")
    public ResponseEntity<?> getHistory(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        try {
            if (from.isAfter(to)) { LocalDate t = from; from = to; to = t; }
            return ResponseEntity.ok(service.getHistory(id, from, to));
        } catch (Exception e) {
            log.error("[MT5-BOT HISTORY] error", e);
            return ResponseEntity.internalServerError().body(err(e));
        }
    }

    /** Body: { lot: 0.01 } — bot phải PAUSED. */
    @PostMapping("/accounts/{id}/lot")
    public ResponseEntity<?> setLot(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            Object lotRaw = body.get("lot");
            if (lotRaw == null) return ResponseEntity.badRequest().body(err("Missing lot"));
            double lot = Double.parseDouble(String.valueOf(lotRaw));
            return ResponseEntity.ok(Map.of("success", true, "account", service.setLot(id, lot)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err(e));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(err(e));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e));
        }
    }

    /** Body: { state: "RUNNING" | "PAUSED" }. Dùng /stop cho STOPPING. */
    @PostMapping("/accounts/{id}/state")
    public ResponseEntity<?> setState(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String s = String.valueOf(body.get("state"));
            Mt5BotState next = Mt5BotState.valueOf(s);
            return ResponseEntity.ok(Map.of("success", true, "account", service.setState(id, next)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err(e));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(err(e));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e));
        }
    }

    /** Body: { passcode: "160625" } — chuyển sang STOPPING, bot sẽ đóng hết lệnh. */
    @PostMapping("/accounts/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String pass = body.get("passcode") == null ? null : String.valueOf(body.get("passcode"));
            return ResponseEntity.ok(Map.of("success", true, "account", service.stopBot(id, pass)));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(e));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(err(e));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e));
        }
    }

    private static Map<String, Object> err(Throwable e) { return Map.of("success", false, "message", e.getMessage()); }
    private static Map<String, Object> err(String m)    { return Map.of("success", false, "message", m); }
}