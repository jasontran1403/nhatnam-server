package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Table(name = "pos_variant")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosVariant {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosProduct product;

    @Column(name = "group_name", nullable = false)
    private String groupName;

    @Column(name = "min_select", nullable = false)
    private Integer minSelect;

    @Column(name = "max_select", nullable = false)
    private Integer maxSelect;

    @Column(name = "allow_repeat", nullable = false)
    @Builder.Default
    private Boolean allowRepeat = true;

    // FIX: typo isDefalut → isDefault
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private Long createdAt;

    @OneToMany(mappedBy = "variant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosVariantIngredient> variantIngredients;

    @Column(name = "is_addon_group")
    private Boolean isAddonGroup = false;
}