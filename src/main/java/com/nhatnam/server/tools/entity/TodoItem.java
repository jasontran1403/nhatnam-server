package com.nhatnam.server.tools.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Một việc cần làm trong bảng Todo.
 *
 * Status: PENDING (đang chờ), IN_PROGRESS (đang làm), DONE (xong), CANCELLED (đã hủy).
 */
@Entity
@Table(name = "todo_item")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TodoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nội dung việc cần làm */
    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    /** Người tạo */
    @Column(name = "creator", nullable = false, length = 100)
    private String creator;

    /** Thời hạn cần hoàn thành (epoch millis) */
    @Column(name = "due_at")
    private Long dueAt;

    /** PENDING | IN_PROGRESS | DONE | CANCELLED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    /**
     * Chủ sở hữu — username của tools_user (khác {@code creator} vì creator là
     * chuỗi hiển thị người dùng tự nhập từ trước khi có auth, có thể có dấu và
     * không luôn khớp username).
     */
    @Column(name = "owner", length = 60)
    private String owner;
}