package com.nhatnam.server.entity.pos;

import com.nhatnam.server.entity.User;
import jakarta.persistence.*;
import lombok.*;

/**
 * Mỗi SELLER account được gắn với 1 PosStore riêng.
 * - SELLER cũ (id 1, 2): gắn với store cũ (legacy).
 * - SELLER mới: được tự động tạo store mới riêng khi tạo tài khoản.
 */
@Entity
@Table(
    name = "seller_store",
    uniqueConstraints = @UniqueConstraint(columnNames = "seller_id")
)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SellerStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private PosStore store;

    /** true = tài khoản cũ (dùng chung kho cũ), false = tài khoản mới (kho riêng) */
    @Column(name = "is_legacy", nullable = false)
    @Builder.Default
    private boolean isLegacy = false;
}
