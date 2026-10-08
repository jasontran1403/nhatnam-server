package com.nhatnam.server.tools.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Một tệp bất kỳ trong kho Tệp.
 *
 * Khác với MediaAsset (chỉ nhận ảnh/video), bảng này nhận MỌI loại file:
 * pdf, xlsx, csv, docx, md, mã nguồn, sql, zip... Vì vậy không có bước
 * detectMediaType ném lỗi, chỉ phân loại để frontend biết mở preview kiểu nào.
 *
 * File thật nằm ở tools.files.storage-dir (mặc định ./data/files), phục vụ qua
 * static resource handler /files/** để có HTTP Range — cần cho tua video và
 * cho pdf.js tải từng phần thay vì nuốt cả file.
 *
 * ddl-auto=update sẽ tự tạo bảng file_asset.
 */
@Entity
@Table(name = "file_asset", indexes = {
        @Index(name = "idx_file_asset_created", columnList = "created_at"),
        @Index(name = "idx_file_asset_ext",     columnList = "ext")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class FileAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tên trên đĩa — UUID + đuôi, không bao giờ trùng và không chứa ký tự lạ */
    @Column(name = "file_name", nullable = false, unique = true, length = 200)
    private String fileName;

    /**
     * Tên hiển thị KÈM đuôi, ví dụ "Báo cáo quý 3 (2).xlsx".
     * Đây là tên người dùng thấy, tìm kiếm và nhận được khi tải về.
     * Không unique ở tầng DB (xóa mềm/đua ghi vẫn có thể trùng) — chống trùng
     * làm ở FileStorageService để còn trả về cảnh báo cho người dùng.
     */
    @Column(name = "original_name", nullable = false, length = 400)
    private String originalName;

    /** Đuôi viết thường KHÔNG có dấu chấm: "xlsx", "pdf". Rỗng nếu file không đuôi. */
    @Column(name = "ext", length = 20)
    private String ext;

    /** Nhóm để frontend chọn kiểu preview: IMAGE|VIDEO|PDF|SHEET|DOC|CODE|TEXT|ARCHIVE|OTHER */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Ảnh thu nhỏ cho file ảnh/video — null với các loại còn lại */
    @Column(name = "thumb_name", length = 200)
    private String thumbName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    /** Đổi khi người dùng lưu lại nội dung đã chỉnh sửa (bảng tính, tài liệu) */
    @Column(name = "updated_at")
    private Long updatedAt;

    /**
     * Chủ sở hữu — username của {@code tools_user} đã upload file này.
     * Chỉ chủ mới thấy khi list. Cho phép null tạm thời để backfill dữ liệu cũ
     * (ToolsBackfillRunner sẽ set 'nguyenhai' cho các dòng null lúc khởi động).
     */
    @Column(name = "owner", length = 60)
    private String owner;
}
