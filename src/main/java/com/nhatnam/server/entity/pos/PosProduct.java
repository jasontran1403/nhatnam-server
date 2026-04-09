package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pos_product")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosProduct {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "is_shopee_food")
    private Boolean isShopeeFood = false;

    @Column(name = "is_grab_food")
    private Boolean isGrabFood = false;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "vat_percent", nullable = false)
    @Builder.Default
    private Integer vatPercent = 0;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    /** Store mà product này thuộc về */
    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = true)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosCategory category;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosVariant> variants = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosAppMenu> appMenus = new ArrayList<>();
}