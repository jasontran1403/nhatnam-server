// src/main/java/com/nhatnam/server/entity/pos/PosShiftImage.java
package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pos_shift_image")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftImage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Ca liên quan (nullable vì mở ca chưa có shift_id)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosShift shift;

    // OPEN | CLOSE
    @Column(name = "image_type", nullable = false, length = 10)
    private String imageType;

    // Đường dẫn file lưu trên disk: /images/shift-images/xxx.png
    @Column(name = "image_path", nullable = false, length = 500)
    private String imagePath;

    // ── Kết quả OCR ──────────────────────────────────────────────
    @Column(name = "ocr_staff_name", length = 100)
    private String ocrStaffName;

    // Ngày extract từ ảnh: "dd/MM/yyyy"
    @Column(name = "ocr_date", length = 20)
    private String ocrDate;

    // Toàn bộ raw JSON trả về từ Python OCR
    @Column(name = "ocr_raw_json", columnDefinition = "TEXT")
    private String ocrRawJson;

    // Trạng thái OCR: PENDING | SUCCESS | FAILED
    @Column(name = "ocr_status", length = 20) @Builder.Default
    private String ocrStatus = "PENDING";

    @Column(name = "ocr_error", columnDefinition = "TEXT")
    private String ocrError;

    // Thời điểm upload (ms epoch)
    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    // HH:mm:ss lúc thao tác
    @Column(name = "action_time", length = 10)
    private String actionTime;

    // store_id để query sau
    @Column(name = "store_id")
    private Long storeId;
}