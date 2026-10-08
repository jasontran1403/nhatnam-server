package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Một task cần làm — thuộc module Todo của VMB.
 *
 * ── Field ─────────────────────────────────────────────
 *   unitName            : tên đơn vị (VD "Công ty A")
 *   description         : mô tả công việc
 *   deadline            : ms epoch — thời hạn phải hoàn thành
 *   status              : PENDING | COMPLETED | CANCELLED
 *   confirmationFile    : ảnh/pdf minh chứng đã hoàn thành (bắt buộc khi COMPLETED)
 *   confirmationOriginal: tên gốc file minh chứng
 *   note                : ghi chú — cập nhật khi complete/extend/cancel
 *   createdAt           : lúc tạo
 *   updatedAt           : lúc thao tác gần nhất
 *
 * Không track lịch sử extension chi tiết ở lần đầu — chỉ cập nhật deadline
 * và updatedAt. Nếu sau này cần audit trail thì thêm bảng vmb_todo_task_event.
 *
 * Bảng: vmb_todo_task
 */
@Entity
@Table(name = "vmb_todo_task", indexes = {
        @Index(name = "idx_vmb_todo_deadline", columnList = "deadline"),
        @Index(name = "idx_vmb_todo_status",   columnList = "status"),
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TodoTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unit_name", nullable = false, length = 200)
    private String unitName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "deadline", nullable = false)
    private Long deadline;

    /** PENDING | COMPLETED | CANCELLED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "confirmation_file", length = 100)
    private String confirmationFile;

    @Column(name = "confirmation_original", length = 255)
    private String confirmationOriginal;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}