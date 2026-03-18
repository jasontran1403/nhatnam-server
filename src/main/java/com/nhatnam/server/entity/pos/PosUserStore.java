// src/main/java/com/nhatnam/server/entity/pos/PosUserStore.java

package com.nhatnam.server.entity.pos;

import com.nhatnam.server.entity.User;
import jakarta.persistence.*;
import lombok.*;

/**
 * Bảng mapping: POS user ↔ PosStore
 *
 * Mỗi user POS thuộc về đúng 1 store.
 * Một store có thể có nhiều user POS (nhiều ca, nhiều nhân viên).
 *
 * Unique constraint trên user_id → 1 user chỉ được gán vào 1 store tại 1 thời điểm.
 */
@Entity
@Table(
        name = "pos_user_store",
        uniqueConstraints = @UniqueConstraint(columnNames = "user_id")
)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PosUserStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** POS user — unique, 1 user chỉ thuộc 1 store */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Store mà user này thuộc về */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private PosStore store;
}