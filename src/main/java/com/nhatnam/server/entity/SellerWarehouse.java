package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Mapping: SELLER → Warehouse.
 * Mỗi user SELLER thuộc về đúng 1 kho.
 */
@Entity
@Table(
    name = "seller_warehouse",
    uniqueConstraints = @UniqueConstraint(columnNames = "seller_id")
)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SellerWarehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;
}
