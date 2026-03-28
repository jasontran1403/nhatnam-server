package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "pos_shift_open_inventory")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftOpenInventory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosShift shift;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private PosIngredient ingredient;

    // Số bịch đầu ca
    @Column(name = "pack_quantity", nullable = false)
    private Integer packQuantity = 0;

    // Số lẻ đầu ca — cho phép thập phân tối đa 2 chữ số (VD: 0.25)
    @Column(name = "unit_quantity", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal unitQuantity = BigDecimal.ZERO;
}
