package com.nhatnam.server.tools.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Một file trong thư viện tài nguyên (ảnh hoặc video).
 *
 * File thật nằm trên đĩa ở thư mục cấu hình bởi tools.media.storage-dir;
 * bảng này chỉ lưu tên file + metadata. Cách này tránh phình database và cho
 * phép phục vụ file qua static resource handler (hỗ trợ HTTP Range — cần thiết
 * để tua video).
 *
 * ddl-auto=update sẽ tự tạo bảng media_asset.
 */
@Entity
@Table(name = "media_asset")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tên file trên đĩa (đã sinh ngẫu nhiên, không trùng) */
    @Column(name = "file_name", nullable = false, unique = true, length = 200)
    private String fileName;

    /** Ảnh thu nhỏ để render lưới — null thì frontend dùng luôn file gốc */
    @Column(name = "thumb_name", length = 200)
    private String thumbName;

    /**
     * Tên hiển thị, mặc định là tên file lúc tải lên, người dùng đổi được.
     * Cũng là tên dùng khi tải về.
     */
    @Column(name = "original_name", length = 300)
    private String originalName;

    /** IMAGE | VIDEO */
    @Column(name = "media_type", nullable = false, length = 10)
    private String mediaType;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** UPLOAD | WATERMARK — biết file nào do người dùng tải lên, file nào do hệ thống sinh */
    @Column(name = "source", length = 20)
    private String source;

    /** Đánh dấu yêu thích để lọc nhanh */
    @Column(name = "favorite", nullable = false)
    @Builder.Default
    private Boolean favorite = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    /**
     * Chủ sở hữu — username của {@code tools_user} đã upload file này.
     * Chỉ chủ mới thấy khi list. Cho phép null tạm thời để backfill dữ liệu cũ
     * (ToolsBackfillRunner sẽ set 'phuongthao' cho các dòng null lúc khởi động).
     */
    @Column(name = "owner", length = 60)
    private String owner;
}
