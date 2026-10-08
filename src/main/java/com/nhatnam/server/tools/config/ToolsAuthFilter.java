package com.nhatnam.server.tools.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.service.ToolsAuthTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Kiểm token cho các endpoint riêng tư của khu Tiện ích.
 *
 * ── FIX 2026-09-13 — chạy trên MỌI /api/tools/ path ─────────────────────
 * Bản trước: skip hoàn toàn cho endpoint public → request.getAttribute()
 * luôn null ở đó, không có cách nào cho public endpoint đọc username khi
 * người dùng có gửi token kèm.
 *
 * Bản này: chạy cho MỌI /api/tools/. Nếu có token hợp lệ → luôn populate
 * ATTR_USERNAME/ATTR_ADMIN. Nếu PROTECTED path mà thiếu/hỏng token → 401.
 * PUBLIC path chấp nhận cả 2 khả năng (có token đúng thì đọc, không thì
 * cho qua).
 *
 * Ứng dụng: /api/tools/vmb/lookup/{id}/reveal (public) đọc username qua
 * ToolsAuthContext.usernameOrNull() để dùng TOTP secret riêng của user
 * (nếu logged in) hoặc fallback yml secret (anonymous).
 *
 * ── Cấu trúc route ─────────────────────────────────────────────────
 *   PROTECTED (bắt buộc token):
 *     /api/tools/media, files, todo, office, users, watermark/save
 *     /api/tools/vmb/admin  (admin unlock 2FA)
 *   PUBLIC (không bắt buộc, token optional):
 *     /api/tools/auth, vmb (trừ /vmb/admin), qr, watermark/add|logo, sign
 *
 * Username gắn vào request attribute {@link #ATTR_USERNAME} để controller
 * đọc qua {@link ToolsAuthContext}.
 */
@Component
@RequiredArgsConstructor
@Log4j2
@Order(10)
public class ToolsAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USERNAME = "tools.auth.username";
    public static final String ATTR_ADMIN    = "tools.auth.admin";

    /**
     * Đường dẫn cần token. Match theo prefix — kiểm TRƯỚC PUBLIC_PREFIXES
     * để "hòn đảo bảo vệ" như /vmb/admin/** vẫn được chặn dù /vmb là public.
     */
    private static final String[] PROTECTED_PREFIXES = {
            "/api/tools/media",
            "/api/tools/files",
            "/api/tools/todo",
            "/api/tools/office",
            "/api/tools/users",
            "/api/tools/watermark/save",
            "/api/tools/vmb",          // Toàn khu Vé máy bay giờ cần đăng nhập (dùng chung tools_user)
    };

    private static final String[] PUBLIC_PREFIXES = {
            "/api/tools/auth",
            "/api/tools/qr",
            "/api/tools/watermark/add",
            "/api/tools/watermark/logo",
            "/api/tools/sign",
    };

    private final ToolsAuthTokenService tokenService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Chỉ skip cho non-tools. Mọi /api/tools/** đều đi qua filter để có cơ hội
        // populate attribute từ token (dù protected hay public).
        return !request.getServletPath().startsWith("/api/tools/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getServletPath();
        boolean isProtected = isProtected(path);

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7).trim() : null;

        var parsed = (token != null && !token.isBlank()) ? tokenService.parse(token) : null;

        if (parsed != null) {
            // Token hợp lệ — luôn populate attribute (kể cả public path)
            request.setAttribute(ATTR_USERNAME, parsed.username());
            request.setAttribute(ATTR_ADMIN,    parsed.admin());
        }

        if (isProtected && parsed == null) {
            writeUnauthorized(response, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại.");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Match TRƯỚC theo PROTECTED (specific hơn), fallback PUBLIC, mặc định
     * unknown → protected (an toàn).
     */
    private static boolean isProtected(String path) {
        for (String pri : PROTECTED_PREFIXES) if (path.startsWith(pri)) return true;
        for (String pub : PUBLIC_PREFIXES)    if (path.startsWith(pub)) return false;
        return true;
    }

    private void writeUnauthorized(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(HttpStatus.UNAUTHORIZED.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        var body = ApiResponse.error(401, msg);
        objectMapper.writeValue(res.getOutputStream(), body);
    }
}
