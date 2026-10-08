package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Nhiệm vụ — hai loại:
 *   ADMIN    → do Admin/SuperAdmin tạo, gán cho nhân viên
 *   PERSONAL → do user/seller/pos tự tạo, chỉ người tạo thấy
 *
 * Trạng thái: NOT_STARTED → IN_PROGRESS → COMPLETED / CANCELLED / PAUSED
 * Ưu tiên   : LOW, MEDIUM, HIGH, URGENT
 */
@Entity
@Table(name = "task", indexes = {
        @Index(name = "idx_task_status",   columnList = "status"),
        @Index(name = "idx_task_priority", columnList = "priority"),
        @Index(name = "idx_task_deadline", columnList = "deadline"),
        @Index(name = "idx_task_created_by", columnList = "created_by"),
        @Index(name = "idx_task_type", columnList = "task_type"),
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Yêu cầu cụ thể của task */
    @Column(name = "requirements", columnDefinition = "TEXT")
    private String requirements;

    /** NOT_STARTED | IN_PROGRESS | COMPLETED | CANCELLED | PAUSED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "NOT_STARTED";

    /** LOW | MEDIUM | HIGH | URGENT */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String priority = "MEDIUM";

    /** Danh mục tự do (ví dụ: Marketing, Kế toán, Kho...) */
    @Column(length = 100)
    private String category;

    /** Hạn chót (epoch millis), tùy chọn — bao gồm cả giờ phút */
    private Long deadline;

    /** Tiến độ 0-100, tự tính từ subtasks nếu có */
    @Column(nullable = false)
    @Builder.Default
    private Integer progress = 0;

    /** Các đầu mục con phải hoàn thành theo thứ tự? */
    @Column(name = "sequential_subtasks", nullable = false)
    @Builder.Default
    private Boolean sequentialSubtasks = false;

    /** ADMIN | PERSONAL */
    @Column(name = "task_type", nullable = false, length = 20)
    @Builder.Default
    private String taskType = "ADMIN";

    /** ID người tạo */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_by_name", length = 100)
    private String createdByName;

    /** Ảnh chụp bằng chứng (đường dẫn, phân cách bằng dấu |) */
    @Column(name = "evidence_images", columnDefinition = "TEXT")
    private String evidenceImages;

    /** Ghi chú xác nhận task đã xong */
    @Column(name = "completion_note", columnDefinition = "TEXT")
    private String completionNote;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
