package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Kho nguyên liệu — mỗi SELLER thuộc về 1 kho.
 * Kho 1 = Kho cũ (seller1, seller2).
 * Kho 2 = Kho mới (các seller tạo từ 22/5/2026 trở đi).
 */
@Entity
@Table(name = "warehouse")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;  // "Kho cũ", "Kho mới"

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
