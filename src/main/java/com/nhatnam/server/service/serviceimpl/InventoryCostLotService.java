package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.entity.InventoryBatch;
import com.nhatnam.server.entity.InventoryCostLot;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.entity.WarehouseIngredientStock;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.InventoryCostLotRepository;
import com.nhatnam.server.repository.WarehouseIngredientStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine quản lý giá vốn theo LÔ (FIFO).
 *
 * Nguồn sự thật của giá vốn là bảng {@code inventory_cost_lot}.
 * Trường {@code costPrice} trên Ingredient / WarehouseIngredientStock chỉ còn là
 * "gương" (giá vốn bình quân của các lô còn hàng) phục vụ hiển thị & báo cáo.
 *
 * Quy ước lô:
 *   - Lô NHẬP  : unitCost = giá nhập, expiryDate = HSD nhập vào, createdAt = now
 *   - Lô OPENING (tồn kho cũ trước khi bật FIFO):
 *         unitCost   = 0
 *         expiryDate = hôm nay + 1 năm
 *         createdAt  = 0  → luôn được xuất trước tiên (FIFO)
 *
 * Quy ước warehouse:
 *   - warehouse == null → kho chung (legacy)
 *   - warehouse != null → theo từng kho
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryCostLotService {

    private final InventoryCostLotRepository         lotRepository;
    private final IngredientRepository               ingredientRepository;
    private final WarehouseIngredientStockRepository warehouseStockRepository;

    /** Kết quả 1 lần lấy hàng từ 1 lô khi xuất FIFO. */
    public record LotAllocation(
            Long lotId,
            String sourceRef,
            BigDecimal unitCost,
            BigDecimal quantity) {

        public BigDecimal amount() {
            return unitCost.multiply(quantity);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NHẬP KHO: tạo lô mới (có HSD + giá vốn tại thời điểm nhập)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tạo lô giá vốn mới khi nhập kho.
     *
     * @param stockBefore tồn kho TRƯỚC khi nhập — dùng để seed lô OPENING nếu tồn
     *                    cũ chưa có lô nào (dữ liệu trước khi bật FIFO).
     * @param expiryDate  HSD của lô nhập (epoch-millis, nullable).
     */
    @Transactional
    public InventoryCostLot addImportLot(
            Ingredient ing, Warehouse warehouse,
            BigDecimal stockBefore, BigDecimal quantity, BigDecimal unitCost,
            Long expiryDate, InventoryBatch batch, long now) {

        seedOpeningLotIfNeeded(ing, warehouse, stockBefore, now);

        InventoryCostLot lot = InventoryCostLot.builder()
                .ingredient(ing)
                .warehouse(warehouse)
                .unitCost(scaleCost(unitCost))
                .expiryDate(expiryDate)
                .originalQuantity(quantity)
                .remainingQuantity(quantity)
                .sourceBatch(batch)
                .sourceRef(batch != null ? batch.getBatchCode() : "IMPORT")
                .createdAt(now)
                .build();
        return lotRepository.save(lot);
    }

    /** Overload giữ tương thích ngược (không truyền HSD). */
    @Transactional
    public InventoryCostLot addImportLot(
            Ingredient ing, Warehouse warehouse,
            BigDecimal stockBefore, BigDecimal quantity, BigDecimal unitCost,
            InventoryBatch batch, long now) {
        return addImportLot(ing, warehouse, stockBefore, quantity, unitCost, null, batch, now);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // XUẤT KHO / XUẤT BÁN: trừ theo FIFO
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Trừ {@code needed} đơn vị theo FIFO và trả về danh sách phân bổ theo lô.
     *
     * @param stockBefore tồn kho TRƯỚC khi trừ — dùng để seed lô OPENING nếu cần.
     */
    @Transactional
    public List<LotAllocation> consumeFifo(
            Ingredient ing, Warehouse warehouse,
            BigDecimal stockBefore, BigDecimal needed, long now) {

        List<LotAllocation> allocations = new ArrayList<>();
        if (needed == null || needed.signum() <= 0) return allocations;

        seedOpeningLotIfNeeded(ing, warehouse, stockBefore, now);

        Long whId = warehouse != null ? warehouse.getId() : null;
        List<InventoryCostLot> lots = lotRepository.findConsumableForUpdate(ing.getId(), whId);

        BigDecimal remaining = needed;
        for (InventoryCostLot lot : lots) {
            if (remaining.signum() <= 0) break;

            BigDecimal take = lot.getRemainingQuantity().min(remaining);
            if (take.signum() <= 0) continue;

            lot.setRemainingQuantity(lot.getRemainingQuantity().subtract(take));
            lotRepository.save(lot);

            allocations.add(new LotAllocation(
                    lot.getId(), lot.getSourceRef(), lot.getUnitCost(), take));
            remaining = remaining.subtract(take);
        }

        // Phòng hờ: nếu tổng lô < tồn thực (lệch dữ liệu) → lấy nốt phần thiếu
        // theo giá vốn bình quân hiện tại để không chặn nghiệp vụ bán hàng.
        if (remaining.signum() > 0) {
            BigDecimal fallbackCost = currentAvgCost(ing, warehouse);
            allocations.add(new LotAllocation(
                    null, "FALLBACK", scaleCost(fallbackCost), remaining));
            log.warn("[FIFO] Thiếu lô cho ingredient={} warehouse={} — bù {} theo giá vốn bình quân {}",
                    ing.getId(), whId, remaining, fallbackCost);
        }

        return allocations;
    }

    /** Giá vốn bình quân của các allocation (tổng tiền / tổng lượng), scale 2. */
    public BigDecimal weightedUnitCost(List<LotAllocation> allocations) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        for (LotAllocation a : allocations) {
            totalQty = totalQty.add(a.quantity());
            totalAmt = totalAmt.add(a.amount());
        }
        if (totalQty.signum() <= 0) return BigDecimal.ZERO;
        return totalAmt.divide(totalQty, 2, RoundingMode.HALF_UP);
    }

    /** Tổng tiền giá vốn của các allocation, scale 2. */
    public BigDecimal totalAmount(List<LotAllocation> allocations) {
        BigDecimal totalAmt = BigDecimal.ZERO;
        for (LotAllocation a : allocations) totalAmt = totalAmt.add(a.amount());
        return totalAmt.setScale(2, RoundingMode.HALF_UP);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HỦY ĐƠN: cộng trả lại đúng lô
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void restoreToLot(
            Long lotId, Ingredient ing, Warehouse warehouse,
            BigDecimal unitCost, BigDecimal quantity, String sourceRef, long now) {

        if (quantity == null || quantity.signum() <= 0) return;

        if (lotId != null) {
            InventoryCostLot lot = lotRepository.findById(lotId).orElse(null);
            if (lot != null) {
                lot.setRemainingQuantity(lot.getRemainingQuantity().add(quantity));
                lotRepository.save(lot);
                return;
            }
        }

        // Lô gốc không còn (hoặc allocation FALLBACK / đơn cũ không có lô) → tạo lô mới.
        InventoryCostLot lot = InventoryCostLot.builder()
                .ingredient(ing)
                .warehouse(warehouse)
                .unitCost(scaleCost(unitCost != null ? unitCost : BigDecimal.ZERO))
                .expiryDate(defaultExpiry())
                .originalQuantity(quantity)
                .remainingQuantity(quantity)
                .sourceRef(sourceRef != null ? sourceRef : "RESTORE")
                .createdAt(now)
                .build();
        lotRepository.save(lot);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Giá vốn bình quân (gương) — cập nhật sau mỗi thay đổi lô
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void recomputeAvgCost(Ingredient ing, Warehouse warehouse, long now) {
        BigDecimal avg = currentAvgCostFromLots(ing, warehouse);
        if (avg == null) return; // không có lô nào còn hàng → giữ nguyên giá vốn cũ

        if (warehouse != null) {
            WarehouseIngredientStock stock = warehouseStockRepository
                    .findByWarehouseIdAndIngredientId(warehouse.getId(), ing.getId())
                    .orElseGet(() -> WarehouseIngredientStock.builder()
                            .warehouse(warehouse).ingredient(ing)
                            .stockQuantity(BigDecimal.ZERO).updatedAt(now).build());
            stock.setCostPrice(avg);
            stock.setUpdatedAt(now);
            warehouseStockRepository.save(stock);
        }
        ing.setCostPrice(avg);
        ing.setUpdatedAt(now);
        ingredientRepository.save(ing);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BACKFILL / OPENING LOT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sinh lô OPENING cho phần tồn kho cũ chưa có lô.
     *
     *   - unitCost   = 0        (tồn kho cũ không có dữ liệu giá vốn theo lô)
     *   - expiryDate = hôm nay + 1 năm
     *   - createdAt  = 0        → FIFO luôn xuất lô này trước
     *
     * Idempotent: chỉ tạo phần thiếu (stock - tổng lô còn hàng).
     * Trả về lô vừa tạo, hoặc null nếu không cần tạo.
     */
    @Transactional
    public InventoryCostLot seedOpeningLotIfNeeded(
            Ingredient ing, Warehouse warehouse, BigDecimal stockBefore, long now) {

        BigDecimal before = stockBefore != null ? stockBefore : BigDecimal.ZERO;
        if (before.signum() <= 0) return null;

        Long whId = warehouse != null ? warehouse.getId() : null;
        BigDecimal available = sumRemaining(ing.getId(), whId);

        BigDecimal missing = before.subtract(available);
        if (missing.signum() <= 0) return null;

        InventoryCostLot opening = InventoryCostLot.builder()
                .ingredient(ing)
                .warehouse(warehouse)
                .unitCost(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .expiryDate(defaultExpiry())
                .originalQuantity(missing)
                .remainingQuantity(missing)
                .sourceRef(InventoryCostLot.SOURCE_OPENING)
                .createdAt(0L) // luôn được xuất trước tiên
                .build();
        return lotRepository.save(opening);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ĐỌC: danh sách lô của 1 nguyên liệu (cho màn hình "Lô hàng")
    // ─────────────────────────────────────────────────────────────────────────

    /** Tất cả lô (kể cả đã xuất hết) của nguyên liệu trong 1 kho, mới nhất trước. */
    public List<InventoryCostLot> listLots(Long ingredientId, Long warehouseId) {
        return lotRepository.findAllForIngredient(ingredientId, warehouseId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Hạn dùng mặc định cho lô tồn kho cũ: hôm nay + 1 năm (đầu ngày, giờ VN). */
    public static Long defaultExpiry() {
        return LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .plusYears(1)
                .atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh"))
                .toInstant()
                .toEpochMilli();
    }

    private BigDecimal sumRemaining(Long ingredientId, Long warehouseId) {
        BigDecimal sum = lotRepository.sumRemaining(ingredientId, warehouseId);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /** Giá vốn bình quân từ lô còn hàng; null nếu không có lô. */
    private BigDecimal currentAvgCostFromLots(Ingredient ing, Warehouse warehouse) {
        Long whId = warehouse != null ? warehouse.getId() : null;
        List<InventoryCostLot> lots = lotRepository.findConsumable(ing.getId(), whId);
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        for (InventoryCostLot l : lots) {
            totalQty = totalQty.add(l.getRemainingQuantity());
            totalAmt = totalAmt.add(l.getRemainingQuantity().multiply(l.getUnitCost()));
        }
        if (totalQty.signum() <= 0) return null;
        return totalAmt.divide(totalQty, 0, RoundingMode.HALF_UP);
    }

    /** Giá vốn bình quân hiện đang lưu (WarehouseIngredientStock hoặc Ingredient). */
    private BigDecimal currentAvgCost(Ingredient ing, Warehouse warehouse) {
        BigDecimal c;
        if (warehouse == null) {
            c = ing.getCostPrice();
        } else {
            c = warehouseStockRepository
                    .findByWarehouseIdAndIngredientId(warehouse.getId(), ing.getId())
                    .map(WarehouseIngredientStock::getCostPrice)
                    .orElse(ing.getCostPrice());
        }
        return c != null ? c : BigDecimal.ZERO;
    }

    private BigDecimal scaleCost(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}