package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Yêu cầu gia hạn deadline.
 * Status: PENDING → APPROVED / REJECTED
 */
@Entity
@Table(name = "deadline_extension", indexes = {
    @Index(name = "idx_de_task",   columnList = "task_id"),
    @Index(name = "idx_de_status", columnList = "status"),
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class DeadlineExtension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "requester_name", length = 200)
    private String requesterName;

    /** Deadline mới đề xuất (epoch millis) */
    @Column(name = "new_deadline", nullable = false)
    private Long newDeadline;

    /** Lý do xin gia hạn */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** PENDING | APPROVED | REJECTED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    /** Ghi chú của admin khi duyệt/từ chối */
    @Column(name = "admin_note", columnDefinition = "TEXT")
    private String adminNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
