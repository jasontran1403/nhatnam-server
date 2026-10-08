package com.nhatnam.server.tools.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.dto.ToolsAuthDtos.*;
import com.nhatnam.server.tools.entity.ToolsUser;
import com.nhatnam.server.tools.repository.ToolsUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

/**
 * Nghiệp vụ tài khoản tools: đăng nhập, thêm/sửa/xóa, đổi mật khẩu,
 * cấp/xoay secret 2FA.
 *
 * Bcrypt work factor 10 — cân bằng giữa tốc độ đăng nhập (~100ms) và độ bền
 * chống brute-force.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class ToolsUserService {

    private final ToolsUserRepository repo;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    // ── Chuẩn hóa ───────────────────────────────────────────────

    public static String norm(String username) {
        if (username == null) return null;
        String t = username.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static void validateUsername(String u) {
        if (u == null || u.isBlank()) throw new ToolsException("Tên đăng nhập không được để trống.");
        if (u.length() < 3 || u.length() > 60) throw new ToolsException("Tên đăng nhập cần từ 3 đến 60 ký tự.");
        if (!u.matches("[a-z0-9._-]+"))
            throw new ToolsException("Tên đăng nhập chỉ gồm chữ thường, số, dấu chấm/gạch dưới/gạch ngang.");
    }

    private static void validatePassword(String p) {
        if (p == null || p.length() < 4)
            throw new ToolsException("Mật khẩu cần ít nhất 4 ký tự.");
        if (p.length() > 200)
            throw new ToolsException("Mật khẩu quá dài.");
    }

    /**
     * Sinh chuỗi base32 32 ký tự — chuẩn secret cho Google Authenticator /
     * Microsoft Authenticator. 32 ký tự base32 = 160 bit entropy, đủ mạnh.
     */
    public static String generateTotpSecret() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) sb.append(BASE32[RANDOM.nextInt(32)]);
        return sb.toString();
    }

    // ── Đăng nhập ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ToolsUser authenticate(String username, String password) {
        String u = norm(username);
        if (u == null || password == null) {
            throw new ToolsException("Sai tên đăng nhập hoặc mật khẩu.");
        }
        var user = repo.findByUsername(u).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getActive())
                || !encoder.matches(password, user.getPasswordHash())) {
            throw new ToolsException("Sai tên đăng nhập hoặc mật khẩu.");
        }
        return user;
    }

    // ── Tra cứu ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ToolsUser mustFindByUsername(String username) {
        String u = norm(username);
        return repo.findByUsername(u == null ? "" : u)
                .orElseThrow(() -> new ToolsException("Không tìm thấy tài khoản."));
    }

    @Transactional(readOnly = true)
    public List<UserView> listAll() {
        return repo.findAllByOrderByIsAdminDescUsernameAsc().stream().map(ToolsUserService::view).toList();
    }

    // ── Tạo mới ─────────────────────────────────────────────────

    @Transactional
    public UserView create(CreateUserRequest req) {
        String u = norm(req.getUsername());
        validateUsername(u);
        validatePassword(req.getPassword());

        if (repo.existsByUsernameIgnoreCase(u)) {
            throw new ToolsException("Tên đăng nhập đã tồn tại.");
        }

        long now = System.currentTimeMillis();
        var user = ToolsUser.builder()
                .username(u)
                .passwordHash(encoder.encode(req.getPassword()))
                .displayName(req.getDisplayName() == null ? "" : req.getDisplayName().trim())
                .isAdmin(Boolean.TRUE.equals(req.getAdmin()))
                .active(true)
                .totpSecret(generateTotpSecret())   // Cấp sẵn secret khi tạo — admin có thể xoay lại
                .createdAt(now)
                .updatedAt(now)
                .build();
        return view(repo.save(user));
    }

    // ── Cập nhật ────────────────────────────────────────────────

    @Transactional
    public UserView update(Long id, UpdateUserRequest req, String actingUsername, boolean actingIsAdmin) {
        var user = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy tài khoản."));

        boolean self = user.getUsername().equalsIgnoreCase(actingUsername);
        if (self && req.getAdmin() != null && !req.getAdmin()) {
            throw new ToolsException("Không thể tự bỏ quyền quản trị của chính mình.");
        }
        if (self && req.getActive() != null && !req.getActive()) {
            throw new ToolsException("Không thể tự vô hiệu hóa tài khoản của chính mình.");
        }

        if (req.getDisplayName() != null) user.setDisplayName(req.getDisplayName().trim());
        if (req.getAdmin() != null)       user.setIsAdmin(req.getAdmin());
        if (req.getActive() != null)      user.setActive(req.getActive());

        if (req.getNewPassword() != null && !req.getNewPassword().isEmpty()) {
            validatePassword(req.getNewPassword());
            user.setPasswordHash(encoder.encode(req.getNewPassword()));
        }
        user.setUpdatedAt(System.currentTimeMillis());
        return view(repo.save(user));
    }

    // ── Đổi mật khẩu của chính mình ─────────────────────────────

    @Transactional
    public void changeOwnPassword(String username, ChangePasswordRequest req) {
        if (req.getCurrentPassword() == null || req.getNewPassword() == null) {
            throw new ToolsException("Thiếu mật khẩu cũ hoặc mới.");
        }
        validatePassword(req.getNewPassword());

        var user = mustFindByUsername(username);
        if (!encoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new ToolsException("Mật khẩu hiện tại không đúng.");
        }
        user.setPasswordHash(encoder.encode(req.getNewPassword()));
        user.setUpdatedAt(System.currentTimeMillis());
        repo.save(user);
    }

    // ── Xóa ────────────────────────────────────────────────────

    @Transactional
    public void delete(Long id, String actingUsername) {
        var user = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy tài khoản."));
        if (user.getUsername().equalsIgnoreCase(actingUsername)) {
            throw new ToolsException("Không thể tự xóa tài khoản của chính mình.");
        }
        repo.deleteById(id);
    }

    // ── TOTP secret ─────────────────────────────────────────────

    /**
     * Lấy secret hiện tại của user (dạng base32). Chỉ admin gọi được ở
     * controller — nếu user thường xem, không cần cho họ thấy secret (họ chỉ
     * cần app authenticator đã cấu hình rồi).
     */
    @Transactional(readOnly = true)
    public String getTotpSecret(Long id) {
        var u = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy tài khoản."));
        return u.getTotpSecret();
    }

    /**
     * Sinh secret mới cho user. Người dùng phải cấu hình lại authenticator app
     * bằng secret mới; secret cũ ngay lập tức vô hiệu.
     */
    @Transactional
    public String regenerateTotpSecret(Long id) {
        var u = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy tài khoản."));
        String s = generateTotpSecret();
        u.setTotpSecret(s);
        u.setUpdatedAt(System.currentTimeMillis());
        repo.save(u);
        log.info("[Tools] Đã xoay TOTP secret cho user {}", u.getUsername());
        return s;
    }

    /**
     * Trả về secret của user (nếu tồn tại và có secret), else null.
     * Dùng cho LookupService khi cần verify TOTP với secret riêng của user.
     */
    @Transactional(readOnly = true)
    public String findTotpSecretByUsername(String username) {
        String u = norm(username);
        if (u == null) return null;
        return repo.findByUsername(u).map(ToolsUser::getTotpSecret).orElse(null);
    }

    // ── Chuyển sang DTO ─────────────────────────────────────────

    public static UserView view(ToolsUser u) {
        return UserView.builder()
                .id(u.getId())
                .username(u.getUsername())
                .displayName(u.getDisplayName())
                .admin(u.getIsAdmin())
                .active(u.getActive())
                .createdAt(u.getCreatedAt())
                .updatedAt(u.getUpdatedAt())
                .build();
    }
}
