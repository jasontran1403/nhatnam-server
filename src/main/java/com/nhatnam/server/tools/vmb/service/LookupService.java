package com.nhatnam.server.tools.vmb.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.service.ToolsUserService;
import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.entity.LookupEntry;
import com.nhatnam.server.tools.vmb.repository.LookupEntryRepository;
import dev.samstevens.totp.code.CodeVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tab Tra cứu: CRUD entry + reveal password có 2FA.
 *
 * Xem javadoc bản trước cho chi tiết về AES-GCM, rate limit, khóa 24h — logic
 * đó giữ nguyên. Bản này chỉ thêm xử lý field {@code agencyCode} (mã đại lý)
 * cho các entry loại ACCOUNT — không nhạy cảm, không mã hóa, tùy chọn.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class LookupService {

    private static final Set<String> TYPES = Set.of("PLAIN", "ACCOUNT");
    private static final long MIN_GAP_MS       = 1000L;
    private static final int  MAX_FAIL         = 5;
    private static final long LOCK_DURATION_MS = 24L * 60 * 60 * 1000;

    private final LookupEntryRepository repo;
    private final VmbCryptoService      crypto;
    private final CodeVerifier          totpVerifier;
    private final ToolsUserService      toolsUserService;

    /** Secret dự phòng khi request không có auth token — người dùng anonymous vẫn dùng được. */
    @Value("${tools.vmb.totp-secret:}")
    private String fallbackTotpSecret;

    private final AtomicInteger failCount   = new AtomicInteger(0);
    private final AtomicLong    lockedUntil = new AtomicLong(0L);
    private final AtomicLong    lastAttempt = new AtomicLong(0L);

    // ── CRUD ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<LookupEntry> search(String q, String type, int page, int size) {
        return repo.search(
                (q != null && !q.isBlank()) ? q.trim() : null,
                (type != null && !type.isBlank()) ? type.trim() : null,
                PageRequest.of(page, Math.min(Math.max(size, 1), 200)));
    }

    @Transactional
    public LookupEntry create(SaveLookupRequest req) {
        validate(req);
        long now = System.currentTimeMillis();
        LookupEntry e = LookupEntry.builder()
                .type(req.getType())
                .keyword(req.getKeyword().trim())
                .details(nz(req.getDetails()))
                .loginUrl(nz(req.getLoginUrl()))
                .loginUsername(nz(req.getLoginUsername()))
                .agencyCode(nz(req.getAgencyCode()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        if ("ACCOUNT".equals(req.getType())) {
            if (req.getPassword() == null || req.getPassword().isEmpty()) {
                throw new ToolsException("Tài khoản cần có mật khẩu.");
            }
            e.setPasswordEnc(crypto.encrypt(req.getPassword()));
        }
        return repo.save(e);
    }

    @Transactional
    public LookupEntry update(Long id, SaveLookupRequest req) {
        validate(req);
        LookupEntry e = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy mục."));
        e.setType(req.getType());
        e.setKeyword(req.getKeyword().trim());
        e.setDetails(nz(req.getDetails()));
        e.setLoginUrl(nz(req.getLoginUrl()));
        e.setLoginUsername(nz(req.getLoginUsername()));
        e.setAgencyCode(nz(req.getAgencyCode()));
        // Chỉ đổi password nếu người dùng gõ vào ô (rỗng = giữ nguyên)
        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            e.setPasswordEnc(crypto.encrypt(req.getPassword()));
        }
        if ("ACCOUNT".equals(req.getType()) && (e.getPasswordEnc() == null || e.getPasswordEnc().isBlank())) {
            throw new ToolsException("Tài khoản cần có mật khẩu.");
        }
        // Nếu chuyển sang PLAIN, xóa hết field liên quan account (kể cả agencyCode)
        if ("PLAIN".equals(req.getType())) {
            e.setLoginUrl(null);
            e.setLoginUsername(null);
            e.setPasswordEnc(null);
            e.setAgencyCode(null);
        }
        e.setUpdatedAt(System.currentTimeMillis());
        return e;
    }

    @Transactional
    public void delete(Long id) { repo.deleteById(id); }

    // ── REVEAL PASSWORD (giữ nguyên logic) ─────────────────────

    /**
     * Reveal password của một mục ACCOUNT.
     *
     * @param username user đang gọi (null nếu anonymous). Nếu user tồn tại
     *                 và có totp_secret riêng → dùng secret đó; nếu không
     *                 fall back về secret ở yml (tools.vmb.totp-secret).
     */
    public RevealPasswordResponse reveal(Long id, String code, String username) {
        long now = System.currentTimeMillis();

        long lockUntil = lockedUntil.get();
        if (now < lockUntil) {
            return RevealPasswordResponse.builder()
                    .state("LOCKED")
                    .lockedUntil(lockUntil)
                    .remaining(0)
                    .build();
        }

        long prev = lastAttempt.get();
        if (now - prev < MIN_GAP_MS) {
            return RevealPasswordResponse.builder()
                    .state("RATE_LIMITED")
                    .remaining(Math.max(0, MAX_FAIL - failCount.get()))
                    .build();
        }
        lastAttempt.set(now);

        // Chọn secret: ưu tiên của user (nếu logged in), sau đó fallback yml
        String secret = null;
        if (username != null) {
            secret = toolsUserService.findTotpSecretByUsername(username);
        }
        if (secret == null || secret.isBlank()) {
            secret = fallbackTotpSecret;
        }
        if (secret == null || secret.isBlank()) {
            throw new ToolsException(
                    "Chưa cấu hình 2FA. Admin cần cấp secret cho tài khoản hoặc đặt tools.vmb.totp-secret trong yml.",
                    "Không có secret nào để verify TOTP");
        }
        if (code == null || !code.matches("\\d{6}") || !totpVerifier.isValidCode(secret, code)) {
            int n = failCount.incrementAndGet();
            if (n >= MAX_FAIL) {
                lockedUntil.set(now + LOCK_DURATION_MS);
                log.warn("[VMB][Reveal] Đã khóa 24h sau {} lần sai liên tiếp", n);
                return RevealPasswordResponse.builder()
                        .state("LOCKED")
                        .lockedUntil(lockedUntil.get())
                        .remaining(0)
                        .build();
            }
            return RevealPasswordResponse.builder()
                    .state("INVALID_CODE")
                    .remaining(MAX_FAIL - n)
                    .build();
        }

        failCount.set(0);
        LookupEntry e = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy mục."));
        if (!"ACCOUNT".equals(e.getType()) || e.getPasswordEnc() == null || e.getPasswordEnc().isBlank()) {
            throw new ToolsException("Mục này không có mật khẩu.");
        }
        return RevealPasswordResponse.builder()
                .state("OK")
                .password(crypto.decrypt(e.getPasswordEnc()))
                .remaining(MAX_FAIL)
                .build();
    }

    public void unlockNow() {
        failCount.set(0);
        lockedUntil.set(0L);
        log.info("[VMB][Reveal] Admin đã mở khóa 2FA");
    }

    public RevealPasswordResponse status() {
        long now = System.currentTimeMillis();
        long lu = lockedUntil.get();
        if (now < lu) {
            return RevealPasswordResponse.builder()
                    .state("LOCKED").lockedUntil(lu).remaining(0).build();
        }
        return RevealPasswordResponse.builder()
                .state("OK")
                .remaining(MAX_FAIL - failCount.get())
                .build();
    }

    // ═══════════════════════════════════════════════════════════

    private static void validate(SaveLookupRequest req) {
        if (req == null) throw new ToolsException("Thiếu dữ liệu.");
        if (!TYPES.contains(req.getType())) throw new ToolsException("Loại không hợp lệ.");
        if (req.getKeyword() == null || req.getKeyword().isBlank())
            throw new ToolsException("Từ khóa không được để trống.");
        if ("ACCOUNT".equals(req.getType())) {
            if (req.getLoginUsername() == null || req.getLoginUsername().isBlank())
                throw new ToolsException("Tài khoản đăng nhập không được để trống.");
        }
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
}
