package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pos_shift_stock_import")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftStockImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private PosShift shift;

    // ── Snapshot thay vì liên kết trực tiếp ──
    @Column(name = "ingredient_id", nullable = true)
    private Long ingredientId;

    @Column(name = "ingredient_name", nullable = false, length = 255)
    private String ingredientName;

    @Column(name = "ingredient_type_name", nullable = false, length = 255)
    private String ingredientTypeName;

    @Column(name = "unit", length = 50)
    private String unit;

    @Column(name = "unit_per_pack")
    private Integer unitPerPack;

    @Column(name = "pack_qty", nullable = false)
    private Integer packQty;

    @Column(name = "imported_at", nullable = false)
    private Long importedAt;
}