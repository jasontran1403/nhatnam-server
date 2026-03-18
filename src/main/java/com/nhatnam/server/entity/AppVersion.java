// src/main/java/com/nhatnam/server/entity/AppVersion.java

package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "app_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "android" hoặc "ios" — lowercase */
    @Column(nullable = false, unique = true, length = 20)
    private String platform;

    /**
     * Version tối thiểu còn được phép dùng.
     * App < minVersion → force update.
     */
    @Column(name = "min_version", nullable = false, length = 20)
    private String minVersion;

    /**
     * Build number tối thiểu còn được phép dùng.
     * Dùng khi cùng version nhưng khác build (ví dụ 1.0.1+8 vs 1.0.1+9).
     * 0 = bỏ qua, chỉ so sánh version.
     */
    @Column(name = "min_build", nullable = false)
    private int minBuild = 0;

    /**
     * Version mới nhất.
     * App < latestVersion nhưng >= minVersion → soft update.
     */
    @Column(name = "latest_version", nullable = false, length = 20)
    private String latestVersion;

    /**
     * Build number mới nhất.
     * Dùng khi cùng version nhưng muốn force update theo build.
     * 0 = bỏ qua, chỉ so sánh version.
     */
    @Column(name = "latest_build", nullable = false)
    private int latestBuild = 0;

    /**
     * App Store / Play Store URL hoặc link APK trực tiếp.
     * iOS:     "https://apps.apple.com/app/idXXXXXXXXX"
     * Android: "https://play.google.com/store/apps/details?id=com.your.package"
     */
    @Column(name = "download_url", length = 512)
    private String downloadUrl;

    /** Nội dung hiển thị trong dialog */
    @Column(length = 1000)
    private String message;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}