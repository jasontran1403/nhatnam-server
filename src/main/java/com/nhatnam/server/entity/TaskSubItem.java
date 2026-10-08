package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Đầu mục con của Task.
 * Mỗi sub-item có trọng số %, tổng max = 100%.
 * Progress của task = SUM(weight) các sub-item đã hoàn thành.
 *
 * Khi task có nhiều assignee + nhiều sub-item:
 *   - Mỗi sub-item có thể gán cho 1 người cụ thể (assigneeId)
 *   - Nếu assigneeId = null → tất cả assignee đều có thể hoàn thành
 */
@Entity
@Table(name = "task_sub_item", indexes = {
        @Index(name = "idx_tsi_task", columnList = "task_id"),
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TaskSubItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** Tên đầu mục */
    @Column(nullable = false, length = 500)
    private String title;

    /** Trọng số phần trăm (0-100) */
    @Column(nullable = false)
    private Integer weight;

    /** Thứ tự hiển thị / thực hiện */
    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;

    /** Người được gán xử lý đầu mục này (null = ai cũng được) */
    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "assignee_name", length = 200)
    private String assigneeName;

    /** Đã hoàn thành? */
    @Column(nullable = false)
    @Builder.Default
    private Boolean completed = false;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "completed_by_name", length = 200)
    private String completedByName;

    @Column(name = "completed_at")
    private Long completedAt;

    /** Ghi chú khi hoàn thành */
    @Column(name = "completion_note", columnDefinition = "TEXT")
    private String completionNote;
}
