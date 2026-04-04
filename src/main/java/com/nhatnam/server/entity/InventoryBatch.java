package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.InventoryAction;
import jakarta.persistence.*;
import lombok.*;

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

    /** Mã phiếu nhà cung cấp (IMPORT) hoặc lý do xuất kho (EXPORT). Null cho ADJUST. */
    @Column(name = "supplier_ref", length = 255)
    private String supplierRef;

    /** URL ảnh phiếu giao hàng — chỉ có ở IMPORT. */
    @Column(name = "receipt_image_url", length = 500)
    private String receiptImageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<InventoryLog> logs = new ArrayList<>();
}