package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "pos_shift_close_inventory")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftCloseInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private PosShift shift;

    @Column(name = "ingredient_id", nullable = true)
    private Long ingredientId;

    @Column(name = "ingredient_name", nullable = false, length = 255)
    private String ingredientName;

    @Column(name = "unit", nullable = false, length = 50)
    private String unit;

    @Column(name = "pack_quantity", nullable = false)
    private Integer packQuantity = 0;

    @Column(name = "unit_quantity", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal unitQuantity = BigDecimal.ZERO;

    @Column(name = "addon_price", precision = 15, scale = 2)
    private BigDecimal addonPrice;
}