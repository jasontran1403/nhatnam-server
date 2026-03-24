package com.nhatnam.server.entity.pos;

import com.nhatnam.server.enumtype.IngredientType;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pos_ingredient")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosIngredient {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "unit_per_pack", nullable = false)
    private Integer unitPerPack = 1;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Column(name = "ingredient_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private IngredientType ingredientType = IngredientType.MAIN;

    @Column(name = "addon_price", precision = 15, scale = 2)
    private BigDecimal addonPrice = BigDecimal.ZERO;

    /** Store mà ingredient này thuộc về */
    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "unit", nullable = false, length = 20)
    @Builder.Default
    private String unit = "Cây";
}