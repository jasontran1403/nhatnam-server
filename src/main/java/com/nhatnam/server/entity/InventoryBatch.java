package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.InventoryAction;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Gom các InventoryLog cùng 1 phiếu thành 1 batch.
 * IMPORT  → batchCode: IS-YYYYMMDD-XXXXXXXXXX
 * EXPORT  → batchCode: ES-YYYYMMDD-XXXXXXXXXX
 * ADJUST  → batchCode: CS-YYYYMMDD-XXXXXXXXXX
 */
@Entity
@Table(name = "inventory_batch")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_code", nullable = false, unique = true, length = 30)
    private String batchCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InventoryAction action; // IMPORT | EXPORT | ADJUST

    @Column(name = "supplier_name", length = 200)
    private String supplierName;

    @Column(name = "total_import_amount", precision = 15, scale = 2)
    private BigDecimal totalImportAmount; // tổng tiền nhập của batch

    // FK tới Supplier (nullable — ADJUST không có NCC)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    // Lý do xuất/nhập (EXPORT/IMPORT), null cho ADJUST
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** Mã phiếu nhà cung cấp (IMPORT) hoặc lý do xuất kho (EXPORT). Null cho ADJUST. */
    @Column(name = "supplier_ref", length = 255)
    private String supplierRef;

    /** URL ảnh phiếu giao hàng — chỉ có ở IMPORT. */
    @Column(name = "receipt_image_url", length = 500)
    private String receiptImageUrl;

    /** URL ảnh phiếu giao hàng — chỉ có ở IMPORT. */
    @Column(name = "receipt_image_urls", columnDefinition = "TEXT")
    private String receiptImageUrls;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<InventoryLog> logs = new ArrayList<>();
}