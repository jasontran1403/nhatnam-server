package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.TelegramDto.LinkInitResponse;
import com.nhatnam.server.dto.TelegramDto.LinkStatus;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.TelegramNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints để user tự quản lý liên kết Telegram của chính họ.
 *
 * KHÔNG cấu hình bot token qua REST — bot token đọc từ application.yml
 * (yêu cầu người dùng đã nêu: không có UI cấu hình bot cho admin).
 *
 * Path: /api/telegram/**  →  không nằm trong PUBLIC_API_PREFIXES → JWT filter kiểm tra.
 * SecurityConfiguration đã có .anyRequest().authenticated() ở cuối → mọi role đã login đều gọi được.
 */
@RestController
@RequestMapping("/api/telegram")
@RequiredArgsConstructor
public class TelegramController {

    private final TelegramNotifier notifier;

    /** Trạng thái liên kết hiện tại của user đang login. */
    @GetMapping("/link/status")
    public ResponseEntity<LinkStatus> status(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(notifier.getStatus(user));
    }

    /** Sinh mã liên kết mới + deep link để mở Telegram. */
    @PostMapping("/link/init")
    public ResponseEntity<LinkInitResponse> init(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(notifier.createLinkToken(user));
    }

    /** Hủy liên kết. */
    @DeleteMapping("/link")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal User user) {
        notifier.unlink(user);
        return ResponseEntity.noContent().build();
    }
}
