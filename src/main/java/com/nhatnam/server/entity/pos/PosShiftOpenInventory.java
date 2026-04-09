package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "pos_shift_open_inventory")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftOpenInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private PosShift shift;

    // ── Thay vì liên kết với PosIngredient, chúng ta lưu snapshot ──
    @Column(name = "ingredient_id", nullable = true)
    private Long ingredientId;                    // giữ lại ID để tra cứu nếu cần

    @Column(name = "ingredient_name", nullable = false, length = 255)
    private String ingredientName;

    @Column(name = "unit", nullable = false, length = 50)
    private String unit;                          // ví dụ: "Cây", "Kg", "Gói"

    @Column(name = "pack_quantity", nullable = false)
    private Integer packQuantity = 0;

    @Column(name = "unit_quantity", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal unitQuantity = BigDecimal.ZERO;

    // Optional: lưu thêm thông tin khác nếu cần
    @Column(name = "addon_price", precision = 15, scale = 2)
    private BigDecimal addonPrice;
}
