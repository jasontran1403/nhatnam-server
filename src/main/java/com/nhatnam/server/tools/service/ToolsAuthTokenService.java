package com.nhatnam.server.tools.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Phát và kiểm JWT cho khu Tiện ích nội bộ.
 *
 * ── 2026-09-15: DÙNG CHUNG SECRET với app chính ────────────────────
 * Trước đây service này có secret riêng (tools.security.jwt-secret) và cách
 * dẫn xuất khóa cũng khác (SHA-256 của raw string). Hệ quả: dù chuỗi secret
 * trong yml giống nhau, HMAC key vẫn khác nhau → 2 filter không verify
 * chéo được token của nhau. Điển hình là FE gắn Bearer thuộc scope Tools
 * cho request /vmb-files/** — filter chính đá vì "signature does not match".
 *
 * Nay dùng chung {@code application.security.jwt.secret-key} và cùng cách
 * dẫn xuất (BASE64 decode) như {@link com.nhatnam.server.config.JwtService}.
 * Một token là verify được cả 2 phía.
 *
 * Payload vẫn giữ: sub (username), scope="tools", admin (boolean), iat, exp.
 * scope="tools" giúp phân biệt token do khu này phát so với token app chính
 * (khi app chính sinh token, không có claim scope) — {@link #parse(String)}
 * vẫn yêu cầu scope="tools" để ngăn nhầm chéo dùng token app chính vào các
 * endpoint đòi user Tools.
 */
@Service
@Log4j2
public class ToolsAuthTokenService {

    public static final String SCOPE = "tools";
    public static final String CLAIM_ADMIN = "admin";

    /** Mặc định 7 ngày, đủ để không phải đăng nhập liên tục nhưng vẫn giới hạn hợp lý */
    private static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private final SecretKey signingKey;
    private final long ttlMs;

    public ToolsAuthTokenService(
            @Value("${application.security.jwt.secret-key}") String rawSecret,
            @Value("${tools.security.jwt-ttl-ms:0}") long ttlMs
    ) {
        this.ttlMs = ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS;

        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu application.security.jwt.secret-key — bắt buộc để phát/kiểm JWT khu Tools.");
        }
        // BASE64 decode — cùng cách dẫn xuất với JwtService của app chính.
        // Không SHA-256 nữa (khác dẫn xuất = khác HMAC key = không verify chéo).
        byte[] keyBytes = Decoders.BASE64.decode(rawSecret);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Tạo token cho một user đã xác thực xong.
     *
     * @param ttlOverrideMs null hoặc &lt;=0 → dùng TTL mặc định. Truyền giá trị
     *                      khác để cấp token ngắn hạn (ví dụ 12 giờ khi user
     *                      không tick "Ghi nhớ đăng nhập").
     */
    public String issue(String username, boolean admin, Long ttlOverrideMs) {
        long now = System.currentTimeMillis();
        long ttl = (ttlOverrideMs != null && ttlOverrideMs > 0) ? ttlOverrideMs : ttlMs;

        Map<String, Object> claims = new HashMap<>();
        claims.put("scope", SCOPE);
        claims.put(CLAIM_ADMIN, admin);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttl))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public record Parsed(String username, boolean admin, long expMs) {}

    /**
     * Kiểm chữ ký + hạn dùng + scope. Trả về null nếu token hỏng/hết hạn/không
     * đúng scope — controller phía trên trả 401.
     *
     * ── Về scope ────────────────────────────────────────────
     * Vẫn yêu cầu scope="tools" ở đây để không cho token do app chính phát
     * đăng nhập vào khu Tools (khu Tools có bảng tools_user riêng, khác users
     * của app chính). Ở chiều ngược lại, filter chính không đòi scope nên
     * token khu Tools vẫn qua được filter chính — đủ cho static /vmb-files.
     */
    public Parsed parse(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            Claims c = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            if (!SCOPE.equals(String.valueOf(c.get("scope")))) return null;

            String sub = c.getSubject();
            if (sub == null || sub.isBlank()) return null;

            Object adminClaim = c.get(CLAIM_ADMIN);
            boolean admin = (adminClaim instanceof Boolean b) ? b : Boolean.parseBoolean(String.valueOf(adminClaim));

            long exp = c.getExpiration() != null ? c.getExpiration().getTime() : 0L;
            return new Parsed(sub, admin, exp);

        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long defaultTtlMs() { return ttlMs; }
}