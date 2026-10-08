package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Lịch sử cập nhật tiến độ task.
 * Mỗi lần user cập nhật % hoặc note sẽ tạo 1 bản ghi.
 */
@Entity
@Table(name = "task_progress_log", indexes = {
    @Index(name = "idx_tpl_task", columnList = "task_id"),
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TaskProgressLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "username", length = 100)
    private String username;

    /** % tiến độ tại thời điểm cập nhật */
    @Column(nullable = false)
    private Integer progress;

    /** Ghi chú mô tả đã làm được gì */
    @Column(columnDefinition = "TEXT")
    private String note;

    /** Đường dẫn ảnh đính kèm (phân cách |) */
    @Column(name = "attachments", columnDefinition = "TEXT")
    private String attachments;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
