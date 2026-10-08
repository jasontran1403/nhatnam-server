package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Tin nhắn thông báo liên quan tới Task.
 *
 * Các loại (type):
 *   ASSIGNED        — Admin giao task cho user
 *   PROGRESS_UPDATE — User cập nhật tiến độ
 *   EXTENSION_REQUEST — User xin gia hạn
 *   EXTENSION_APPROVED — Admin duyệt gia hạn
 *   EXTENSION_REJECTED — Admin từ chối gia hạn
 *   TASK_COMPLETED  — User đánh dấu hoàn thành
 *   GENERAL         — Thông báo chung
 */
@Entity
@Table(name = "task_message", indexes = {
    @Index(name = "idx_tm_recipient",  columnList = "recipient_id"),
    @Index(name = "idx_tm_read",       columnList = "is_read"),
    @Index(name = "idx_tm_task",       columnList = "task_id"),
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TaskMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "task_title", length = 500)
    private String taskTitle;

    /** ID người nhận */
    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    /** ID người gửi */
    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "sender_name", length = 200)
    private String senderName;

    /** Loại tin nhắn */
    @Column(nullable = false, length = 30)
    private String type;

    /** Nội dung tin nhắn */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
