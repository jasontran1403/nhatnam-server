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

    @Column(name = "unit_per_pack", nullable = false, precision = 12, scale = 3)
    private BigDecimal unitPerPack = BigDecimal.ONE;

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

    // ═══════════════════════════════════════════════════════════════════════
    // Ý 1 — BÁN MÓN NÓNG
    // Bịch xé ra bán theo phần cho món nóng. Số bịch tiêu hao trong ca
    // = ceil(tổng số phần / hotSalesPerBag). Phần tiêu hao này KHÔNG đưa vào
    // đối soát tồn cuối ca (xử lý riêng ở tầng report).
    // Nguyên liệu cũ: hotSaleEnabled = false → giữ nguyên 100% logic cũ.
    // ═══════════════════════════════════════════════════════════════════════
    @Column(name = "hot_sale_enabled")
    @Builder.Default
    private Boolean hotSaleEnabled = false;

    /** Số phần bán được từ 1 bịch (vd: 4). */
    @Column(name = "hot_sales_per_bag")
    private Integer hotSalesPerBag;

    /** Định lượng mỗi phần (vd: 0.15). */
    @Column(name = "hot_qty_per_sale", precision = 10, scale = 4)
    private BigDecimal hotQtyPerSale;

    /** Đơn vị tính của phần bán nóng (vd: "kg"). */
    @Column(name = "hot_sale_unit", length = 20)
    private String hotSaleUnit;

    // ═══════════════════════════════════════════════════════════════════════
    // Ý 2 — BÁN XÉ LẺ
    // Bật cho phép bán lẻ từng miếng của 1 bịch, giá nhập tay.
    // Tỷ lệ quy đổi (1 bịch = mấy miếng) DÙNG LẠI unitPerPack sẵn có → kho
    // đối soát theo "miếng" như hiện tại, không thêm nguồn dữ liệu thứ 2.
    // "Đơn vị bịch" dùng lại field `unit`.
    // Nguyên liệu cũ: looseSaleEnabled = false → giữ nguyên 100% logic cũ.
    // ═══════════════════════════════════════════════════════════════════════
    @Column(name = "loose_sale_enabled")
    @Builder.Default
    private Boolean looseSaleEnabled = false;

    /** Ý 2: đơn vị nhỏ nhất khi xé lẻ (Miếng, Kg, Lát, Cái, Cây, Cục...). */
    @Column(name = "loose_unit", length = 20)
    private String looseUnit;
}