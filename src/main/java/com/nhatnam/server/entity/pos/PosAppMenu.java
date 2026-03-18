package com.nhatnam.server.entity.pos;

import com.nhatnam.server.enumtype.AppPlatform;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pos_app_menu",
        uniqueConstraints = @UniqueConstraint(columnNames = {"product_id","platform"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosAppMenu {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosProduct product;

    // SHOPEE_FOOD | GRAB_FOOD
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppPlatform platform;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "is_active")
    private Boolean isActive = true;
}
