package com.nhatnam.server.tools.config;

import com.nhatnam.server.tools.entity.ToolsUser;
import com.nhatnam.server.tools.repository.ToolsUserRepository;
import com.nhatnam.server.tools.service.ToolsUserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chạy 1 lần khi khởi động backend:
 *
 *   1. Seed 2 tài khoản mặc định NẾU bảng tools_user còn trống.
 *   2. Gán owner cho dữ liệu cũ CHỈ khi cột owner đang null.
 *   3. Cấp {@code totp_secret} cho user nào chưa có — cả bản seed mới lẫn user
 *      cũ đã tồn tại trước Phase C. Idempotent nhờ WHERE totp_secret IS NULL.
 */
@Configuration
@Log4j2
@RequiredArgsConstructor
public class ToolsBackfillRunner {

    @PersistenceContext
    private EntityManager em;

    @Bean
    public ApplicationRunner toolsBackfill(
            ToolsUserRepository repo,
            @Value("${tools.security.seed-admin-password:1412}") String adminPass,
            @Value("${tools.security.seed-user-password:123}") String userPass
    ) {
        return args -> {
            seedUsersIfEmpty(repo, adminPass, userPass);
            backfillOwners();
            backfillTotpSecrets(repo);
        };
    }

    @Transactional
    protected void seedUsersIfEmpty(ToolsUserRepository repo, String adminPass, String userPass) {
        if (repo.count() > 0) return;

        var encoder = new BCryptPasswordEncoder(10);
        long now = System.currentTimeMillis();

        repo.save(ToolsUser.builder()
                .username("nguyenhai")
                .passwordHash(encoder.encode(adminPass))
                .displayName("Nguyen Hai")
                .isAdmin(true)
                .active(true)
                .totpSecret(ToolsUserService.generateTotpSecret())
                .createdAt(now)
                .updatedAt(now)
                .build());

        repo.save(ToolsUser.builder()
                .username("phuongthao")
                .passwordHash(encoder.encode(userPass))
                .displayName("Phuong Thao")
                .isAdmin(false)
                .active(true)
                .totpSecret(ToolsUserService.generateTotpSecret())
                .createdAt(now)
                .updatedAt(now)
                .build());

        log.info("[Tools][Seed] Đã tạo 2 tài khoản mặc định (nguyenhai admin, phuongthao). "
                + "Đăng nhập rồi đổi mật khẩu ngay. TOTP secret có thể xem/xoay trong Quản lý người dùng.");
    }

    @Transactional
    protected void backfillOwners() {
        int m = safeUpdate("UPDATE media_asset SET owner = 'phuongthao' WHERE owner IS NULL");
        int f = safeUpdate("UPDATE file_asset  SET owner = 'nguyenhai'  WHERE owner IS NULL");
        int t1 = safeUpdate(
                "UPDATE todo_item t LEFT JOIN tools_user u ON LOWER(t.creator) = u.username " +
                "SET t.owner = u.username WHERE t.owner IS NULL AND u.username IS NOT NULL"
        );
        int t2 = safeUpdate(
                "UPDATE todo_item SET owner = 'nguyenhai' WHERE owner IS NULL"
        );
        if (m + f + t1 + t2 > 0) {
            log.info("[Tools][Backfill] Đã gán owner — media:{} file:{} todo(map):{} todo(default):{}",
                    m, f, t1, t2);
        }
    }

    /**
     * Cấp TOTP secret cho user chưa có. Chạy TỪNG user thay vì UPDATE SQL vì
     * mỗi secret phải random khác nhau — không thể dùng 1 SQL SET.
     */
    @Transactional
    protected void backfillTotpSecrets(ToolsUserRepository repo) {
        int n = 0;
        long now = System.currentTimeMillis();
        for (var u : repo.findAll()) {
            if (u.getTotpSecret() == null || u.getTotpSecret().isBlank()) {
                u.setTotpSecret(ToolsUserService.generateTotpSecret());
                u.setUpdatedAt(now);
                repo.save(u);
                n++;
            }
        }
        if (n > 0) log.info("[Tools][Backfill] Đã cấp TOTP secret cho {} user cũ", n);
    }

    private int safeUpdate(String sql) {
        try {
            return em.createNativeQuery(sql).executeUpdate();
        } catch (Exception e) {
            log.debug("[Tools][Backfill] bỏ qua '{}': {}", sql, e.getMessage());
            return 0;
        }
    }
}
