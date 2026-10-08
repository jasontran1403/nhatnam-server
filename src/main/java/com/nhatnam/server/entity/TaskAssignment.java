package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Gán user vào task. Một task có thể gán cho nhiều user.
 */
@Entity
@Table(name = "task_assignment",
    uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "user_id"}),
    indexes = {
        @Index(name = "idx_ta_user",   columnList = "user_id"),
        @Index(name = "idx_ta_task",   columnList = "task_id"),
    })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TaskAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "username", length = 100)
    private String username;

    @Column(name = "full_name", length = 200)
    private String fullName;

    @Column(name = "assigned_at", nullable = false)
    private Long assignedAt;
}
