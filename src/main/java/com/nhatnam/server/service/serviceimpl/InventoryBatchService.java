package com.nhatnam.server.service.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.request.ManualExportRequest;
import com.nhatnam.server.dto.request.ManualImportRequest;
import com.nhatnam.server.dto.request.StockCheckRequest;
import com.nhatnam.server.dto.response.InventoryBatchDetailResponse;
import com.nhatnam.server.dto.response.InventoryBatchSummaryResponse;
import com.nhatnam.server.dto.response.InventoryCostLotResponse;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.InventoryAction;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Log4j2
public class InventoryBatchService {

    private final InventoryBatchRepository          batchRepository;
    private final InventoryLogRepository            logRepository;
    private final IngredientRepository              ingredientRepository;
    private final ImportBatchSequenceRepository     seqRepository;
    private final ObjectMapper                      objectMapper;
    private final SupplierRepository                supplierRepository;
    private final SellerWarehouseRepository         sellerWarehouseRepository;
    private final WarehouseIngredientStockRepository warehouseStockRepository;
    private final WarehouseRepository               warehouseRepository;
    private final InventoryCostLotService           costLotService;

    // ── Sequence ──────────────────────────────────────────────────────────────

    @Transactional
    public String nextBatchCode(String prefix) {
        String dateKey = prefix + LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        ImportBatchSequence seq = seqRepository
                .findByDateKeyForUpdate(dateKey)
                .orElseGet(() -> ImportBatchSequence.builder()
                        .dateKey(dateKey).lastSeq(0L).build());

        seq.setLastSeq(seq.getLastSeq() + 1);
        seqRepository.save(seq);

        return dateKey + "-" + String.format("%010d", seq.getLastSeq());
    }

    // ── Warehouse helper ──────────────────────────────────────────────────────

    /**
     * Lấy Warehouse của seller. Nếu không có mapping → null (legacy, dùng ingredient.stockQuantity).
     */
    private Warehouse resolveWarehouse(User actor) {
        return sellerWarehouseRepository.findBySellerId(actor.getId())
                .map(SellerWarehouse::getWarehouse)
                .orElse(null);
    }

    /**
     * Lấy stockQuantity cho ingredient theo warehouse.
     * - warehouse == null (legacy): dùng ingredient.stockQuantity
     * - warehouse != null (mới): dùng WarehouseIngredientStock
     */
    private BigDecimal getStock(Ingredient ing, Warehouse warehouse) {
        if (warehouse == null) return ing.getStockQuantity();
        return warehouseStockRepository
                .findByWarehouseIdAndIngredientId(warehouse.getId(), ing.getId())
                .map(WarehouseIngredientStock::getStockQuantity)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Cập nhật stockQuantity sau khi import/export/adjust.
     * - warehouse == null: cập nhật ingredient.stockQuantity
     * - warehouse != null: cập nhật/tạo WarehouseIngredientStock
     */
    private void updateStock(Ingredient ing, Warehouse warehouse, BigDecimal newQty, long now) {
        if (warehouse == null) {
            ing.setStockQuantity(newQty);
            ing.setUpdatedAt(now);
            ingredientRepository.save(ing);
        } else {
            WarehouseIngredientStock stock = warehouseStockRepository
                    .findByWarehouseIdAndIngredientId(warehouse.getId(), ing.getId())
                    .orElseGet(() -> WarehouseIngredientStock.builder()
                            .warehouse(warehouse)
                            .ingredient(ing)
                            .stockQuantity(BigDecimal.ZERO)
                            .updatedAt(now)
                            .build());
            stock.setStockQuantity(newQty);
            stock.setUpdatedAt(now);
            warehouseStockRepository.save(stock);
        }
    }

    /**
     * Lấy giá vốn hiện tại cho ingredient theo warehouse.
     * - warehouse == null (legacy): dùng ingredient.costPrice
     * - warehouse != null: dùng WarehouseIngredientStock.costPrice
     * Trả về ZERO nếu chưa có.
     */
    private BigDecimal getCost(Ingredient ing, Warehouse warehouse) {
        BigDecimal c;
        if (warehouse == null) {
            c = ing.getCostPrice();
        } else {
            c = warehouseStockRepository
                    .findByWarehouseIdAndIngredientId(warehouse.getId(), ing.getId())
                    .map(WarehouseIngredientStock::getCostPrice)
                    .orElse(null);
        }
        return c != null ? c : BigDecimal.ZERO;
    }

    /**
     * Lưu giá vốn mới (đã làm tròn đến đồng) sau khi nhập kho.
     * - warehouse != null: lưu vào WarehouseIngredientStock (chuẩn theo kho)
     * - LUÔN mirror vào ingredient.costPrice để các báo cáo global có giá trị hiển thị.
     */
    private void updateCost(Ingredient ing, Warehouse warehouse, BigDecimal newCost, long now) {
        if (warehouse != null) {
            WarehouseIngredientStock stock = warehouseStockRepository
                    .findByWarehouseIdAndIngredientId(warehouse.getId(), ing.getId())
                    .orElseGet(() -> WarehouseIngredientStock.builder()
                            .warehouse(warehouse)
                            .ingredient(ing)
                            .stockQuantity(BigDecimal.ZERO)
                            .updatedAt(now)
                            .build());
            stock.setCostPrice(newCost);
            stock.setUpdatedAt(now);
            warehouseStockRepository.save(stock);
        }
        // Mirror lên ingredient (đại diện) — cũng là nơi lưu chính thức cho seller legacy
        ing.setCostPrice(newCost);
        ing.setUpdatedAt(now);
        ingredientRepository.save(ing);
    }

    /**
     * Tính giá vốn trung bình gia quyền mới, làm tròn đến đồng (HALF_UP).
     *
     *   newCost = (beforeQty * oldCost + addedQty * unitPrice) / (beforeQty + addedQty)
     *
     * Nếu tồn trước = 0 → newCost = unitPrice (làm tròn).
     */
    private BigDecimal computeWeightedAvgCost(
            BigDecimal beforeQty, BigDecimal oldCost,
            BigDecimal addedQty, BigDecimal unitPrice) {

        BigDecimal before = beforeQty != null ? beforeQty.max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal oc     = oldCost   != null ? oldCost   : BigDecimal.ZERO;
        BigDecimal added  = addedQty  != null ? addedQty  : BigDecimal.ZERO;
        BigDecimal price  = unitPrice != null ? unitPrice : BigDecimal.ZERO;

        BigDecimal totalQty = before.add(added);
        if (totalQty.signum() <= 0) {
            return price.setScale(0, java.math.RoundingMode.HALF_UP);
        }
        BigDecimal totalValue = before.multiply(oc).add(added.multiply(price));
        return totalValue.divide(totalQty, 0, java.math.RoundingMode.HALF_UP);
    }

    // ── IMPORT ────────────────────────────────────────────────────────────────

    @Transactional
    public InventoryBatch createImportBatch(
            ManualImportRequest request, User actor, List<String> receiptImageUrls) {

        String batchCode = nextBatchCode("IS-");
        long   now       = System.currentTimeMillis();

        Warehouse warehouse = resolveWarehouse(actor);

        String supplierName = resolveSupplierName(
                request.getSupplierName(), request.getSupplierId(), request.getSupplierRef());
        String note     = firstNonBlank(request.getNote(), request.getSupplierRef());
        String logReason = buildReason(batchCode, supplierName);
        String firstUrl = (receiptImageUrls != null && !receiptImageUrls.isEmpty())
                ? receiptImageUrls.get(0) : null;

        Supplier supplier = null;
        if (request.getSupplierId() != null)
            supplier = supplierRepository.findById(request.getSupplierId()).orElse(null);

        BigDecimal totalImportAmount = BigDecimal.ZERO;

        InventoryBatch batch = InventoryBatch.builder()
                .batchCode(batchCode)
                .action(InventoryAction.IMPORT)
                .supplierRef(supplierName)
                .supplierName(supplierName)
                .supplier(supplier)
                .note(note)
                .receiptImageUrl(firstUrl)
                .receiptImageUrls(urlsToJson(receiptImageUrls))
                .createdBy(actor)
                .warehouse(warehouse)   // gắn warehouse (null = legacy)
                .createdAt(now)
                .build();
        batchRepository.save(batch);

        List<InventoryLog> logs = new ArrayList<>();

        for (ManualImportRequest.ImportItem item : request.getItems()) {
            Ingredient ing = ingredientRepository
                    .findByIdAndIsActiveTrue(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException(
                            "Ingredient not found: " + item.getIngredientId()));

            // ── Bắt buộc nhập giá vốn ( > 0 ) khi nhập kho ──────────────────────
            BigDecimal unitPrice = item.getUnitPrice();
            if (unitPrice == null || unitPrice.signum() <= 0) {
                throw new RuntimeException(
                        "Vui lòng nhập giá vốn (> 0) cho nguyên liệu: " + ing.getName());
            }

            BigDecimal before = getStock(ing, warehouse);
            BigDecimal added  = item.getQuantity();
            BigDecimal after  = before.add(added);

            updateStock(ing, warehouse, after, now);

            // ── FIFO: tạo lô giá vốn mới cho lần nhập này ──────────────────────
            //   (seed lô OPENING cho tồn cũ nếu đây là lần đầu có lô)
            //   Lô mới mang HSD + giá vốn tại thời điểm nhập.
            costLotService.addImportLot(
                    ing, warehouse, before, added, unitPrice,
                    item.getExpiryDate(),   // ← HSD của lô nhập
                    batch, now);
            // Cập nhật giá vốn bình quân (gương) từ các lô còn hàng
            costLotService.recomputeAvgCost(ing, warehouse, now);

            if (warehouse == null) {
                // legacy: cập nhật expiryDate trên ingredient
                ing.setExpiryDate(effectiveExpiry(ing.getExpiryDate(), item.getExpiryDate()));
                ing.setUpdatedAt(now);
                ingredientRepository.save(ing);
            }

            BigDecimal lineAmount = unitPrice.multiply(added);
            totalImportAmount     = totalImportAmount.add(lineAmount);

            logs.add(InventoryLog.builder()
                    .ingredient(ing)
                    .batch(batch)
                    .action(InventoryAction.IMPORT)
                    .quantity(added)
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .reason(logReason)
                    .unitPrice(unitPrice)
                    .lineAmount(lineAmount)
                    .receiptImageUrl(firstUrl)
                    .receiptImageUrls(urlsToJson(receiptImageUrls))
                    .user(actor)
                    .warehouse(warehouse)
                    .createdAt(now)
                    .build());
        }

        batch.setTotalImportAmount(totalImportAmount);
        batchRepository.save(batch);
        batch.setLogs(logs);
        return batch;
    }

    // ── EXPORT ────────────────────────────────────────────────────────────────

    @Transactional
    public InventoryBatch createExportBatch(ManualExportRequest request, User actor) {
        String batchCode = nextBatchCode("ES-");
        long   now       = System.currentTimeMillis();

        Warehouse warehouse = resolveWarehouse(actor);

        String supplierName = resolveSupplierName(request.getSupplierName(), request.getSupplierId(), null);
        String note     = firstNonBlank(request.getNote(), request.getReason());
        String logReason = buildReason(batchCode, supplierName);

        Supplier supplier = null;
        if (request.getSupplierId() != null)
            supplier = supplierRepository.findById(request.getSupplierId()).orElse(null);

        InventoryBatch batch = InventoryBatch.builder()
                .batchCode(batchCode)
                .action(InventoryAction.EXPORT)
                .supplierRef(supplierName)
                .supplierName(supplierName)
                .supplier(supplier)
                .note(note)
                .createdBy(actor)
                .warehouse(warehouse)
                .createdAt(now)
                .build();
        batchRepository.save(batch);

        List<InventoryLog> logs = new ArrayList<>();

        for (ManualExportRequest.ExportItem item : request.getItems()) {
            Ingredient ing = ingredientRepository
                    .findByIdAndIsActiveTrue(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException(
                            "Ingredient not found: " + item.getIngredientId()));

            BigDecimal before = getStock(ing, warehouse);
            BigDecimal qty    = item.getQuantity();
            BigDecimal after  = before.subtract(qty);

            if (after.compareTo(BigDecimal.ZERO) < 0)
                throw new RuntimeException(String.format(
                        "Không đủ tồn kho '%s' (còn: %s, cần: %s %s)",
                        ing.getName(), before, qty, ing.getUnit()));

            // ── FIFO: trừ theo lô cũ trước ─────────────────────────────────────
            List<InventoryCostLotService.LotAllocation> allocs =
                    costLotService.consumeFifo(ing, warehouse, before, qty, now);
            BigDecimal unitCost = costLotService.weightedUnitCost(allocs); // giá vốn bình quân FIFO
            BigDecimal lineAmt  = costLotService.totalAmount(allocs);      // tổng giá vốn xuất

            updateStock(ing, warehouse, after, now);
            costLotService.recomputeAvgCost(ing, warehouse, now);

            logs.add(InventoryLog.builder()
                    .ingredient(ing)
                    .batch(batch)
                    .action(InventoryAction.EXPORT)
                    .quantity(qty.negate())
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .reason(logReason)
                    .unitPrice(unitCost)       // giá vốn FIFO tại thời điểm xuất
                    .lineAmount(lineAmt)       // tổng giá vốn xuất theo FIFO
                    .user(actor)
                    .warehouse(warehouse)
                    .createdAt(now)
                    .build());
        }

        logRepository.saveAll(logs);
        batch.setLogs(logs);
        return batch;
    }

    // ── ADJUST ────────────────────────────────────────────────────────────────

    @Transactional
    public InventoryBatch createAdjustBatch(StockCheckRequest request, User actor) {
        String batchCode = nextBatchCode("CS-");
        long   now       = System.currentTimeMillis();

        Warehouse warehouse = resolveWarehouse(actor);

        InventoryBatch batch = InventoryBatch.builder()
                .batchCode(batchCode)
                .action(InventoryAction.ADJUST)
                .createdBy(actor)
                .warehouse(warehouse)
                .createdAt(now)
                .build();
        batchRepository.save(batch);

        List<InventoryLog> logs = new ArrayList<>();

        for (StockCheckRequest.CheckItem item : request.getItems()) {
            Ingredient ing = ingredientRepository
                    .findByIdAndIsActiveTrue(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException(
                            "Ingredient not found: " + item.getIngredientId()));

            BigDecimal before = getStock(ing, warehouse);
            BigDecimal actual = item.getActualQuantity();
            BigDecimal diff   = actual.subtract(before);

            if (diff.compareTo(BigDecimal.ZERO) != 0) {
                updateStock(ing, warehouse, actual, now);
            }

            logs.add(InventoryLog.builder()
                    .ingredient(ing)
                    .batch(batch)
                    .action(InventoryAction.ADJUST)
                    .quantity(diff)
                    .quantityBefore(before)
                    .quantityAfter(actual)
                    .reason(batchCode)
                    .user(actor)
                    .warehouse(warehouse)
                    .createdAt(now)
                    .build());
        }

        logRepository.saveAll(logs);
        batch.setLogs(logs);
        return batch;
    }

    // ── Query (filter theo warehouse) ─────────────────────────────────────────

    public Page<InventoryBatchSummaryResponse> listBatches(
            String action, int page, int size, User actor) {

        PageRequest pr = PageRequest.of(page, size, Sort.by("createdAt").descending());

        // Lấy warehouse của actor
        Optional<SellerWarehouse> sw = sellerWarehouseRepository.findBySellerId(actor.getId());

        Page<InventoryBatch> batches;

        if (sw.isPresent()) {
            // Seller mới (có mapping) → chỉ lấy batch thuộc warehouse của mình
            Long warehouseId = sw.get().getWarehouse().getId();
            batches = (action == null || action.isBlank())
                    ? batchRepository.findByWarehouseIdOrderByCreatedAtDesc(warehouseId, pr)
                    : batchRepository.findByWarehouseIdAndActionOrderByCreatedAtDesc(
                    warehouseId, InventoryAction.valueOf(action.toUpperCase()), pr);
        } else {
            // Legacy seller (không có mapping) → lấy batch KHÔNG có warehouse (warehouse IS NULL)
            batches = (action == null || action.isBlank())
                    ? batchRepository.findByWarehouseIsNullOrderByCreatedAtDesc(pr)
                    : batchRepository.findByWarehouseIsNullAndActionOrderByCreatedAtDesc(
                    InventoryAction.valueOf(action.toUpperCase()), pr);
        }

        return batches.map(this::toSummary);
    }

    public InventoryBatchDetailResponse getBatchDetail(Long id) {
        InventoryBatch batch = batchRepository.findByIdWithLogs(id)
                .orElseThrow(() -> new RuntimeException("Batch not found: " + id));
        return toDetail(batch);
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private InventoryBatchSummaryResponse toSummary(InventoryBatch b) {
        return InventoryBatchSummaryResponse.builder()
                .id(b.getId())
                .batchCode(b.getBatchCode())
                .action(b.getAction().name())
                .supplierRef(b.getSupplierRef())
                .receiptImageUrl(b.getReceiptImageUrl())
                .totalImportAmount(b.getTotalImportAmount())
                .createdByName(b.getCreatedBy() != null ? b.getCreatedBy().getUsername() : "")
                .createdAt(b.getCreatedAt())
                .totalItems(b.getLogs() != null ? b.getLogs().size() : 0)
                .build();
    }

    private InventoryBatchDetailResponse toDetail(InventoryBatch b) {
        List<InventoryBatchDetailResponse.LogLineResponse> lines = new ArrayList<>();
        if (b.getLogs() != null) {
            for (InventoryLog log : b.getLogs()) {
                lines.add(InventoryBatchDetailResponse.LogLineResponse.builder()
                        .ingredientId(log.getIngredient() != null ? log.getIngredient().getId() : null)
                        .ingredientName(log.getIngredient() != null ? log.getIngredient().getName() : "")
                        .unit(log.getIngredient() != null ? log.getIngredient().getUnit() : "")
                        .quantity(log.getQuantity())
                        .quantityBefore(log.getQuantityBefore())
                        .quantityAfter(log.getQuantityAfter())
                        .unitPrice(log.getUnitPrice())
                        .lineAmount(log.getLineAmount())
                        .build());
            }
        }
        return InventoryBatchDetailResponse.builder()
                .id(b.getId())
                .batchCode(b.getBatchCode())
                .action(b.getAction().name())
                .supplierRef(b.getSupplierRef())
                .receiptImageUrl(b.getReceiptImageUrl())
                .imageUrls(jsonToUrls(b.getReceiptImageUrls()))
                .totalImportAmount(b.getTotalImportAmount())
                .createdByName(b.getCreatedBy() != null ? b.getCreatedBy().getUsername() : "")
                .createdAt(b.getCreatedAt())
                .lines(lines)
                .build();
    }

    // ── COST LOTS (danh sách lô giá vốn của 1 nguyên liệu) ───────────────────

    /**
     * Danh sách lô giá vốn của 1 nguyên liệu, theo đúng kho của seller đang đăng nhập.
     * Trả về CẢ lô đã xuất hết (remaining = 0); lô mới nhất trước.
     *
     * Lô "OPENING" = tồn kho cũ được backfill (giá vốn 0, HSD +1 năm).
     */
    public List<InventoryCostLotResponse> listCostLots(Long ingredientId, User actor) {
        Ingredient ing = ingredientRepository.findById(ingredientId)
                .orElseThrow(() -> new RuntimeException("Ingredient not found: " + ingredientId));

        Warehouse warehouse = resolveWarehouse(actor);
        Long whId = warehouse != null ? warehouse.getId() : null;

        long now = System.currentTimeMillis();

        return costLotService.listLots(ingredientId, whId).stream()
                .map(l -> {
                    BigDecimal remaining = l.getRemainingQuantity();
                    return InventoryCostLotResponse.builder()
                            .id(l.getId())
                            .ingredientId(ing.getId())
                            .ingredientName(ing.getName())
                            .unit(ing.getUnit())
                            .sourceRef(l.getSourceRef())
                            .opening(InventoryCostLot.SOURCE_OPENING.equals(l.getSourceRef()))
                            .unitCost(l.getUnitCost())
                            .originalQuantity(l.getOriginalQuantity())
                            .remainingQuantity(remaining)
                            .remainingValue(remaining.multiply(l.getUnitCost()))
                            .expiryDate(l.getExpiryDate())
                            .createdAt(l.getCreatedAt())
                            .depleted(remaining.signum() <= 0)
                            .expired(l.getExpiryDate() != null && l.getExpiryDate() < now)
                            .build();
                })
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveSupplierName(String nameFromRequest, Long supplierId, String fallback) {
        if (nameFromRequest != null && !nameFromRequest.isBlank()) return nameFromRequest.trim();
        if (supplierId != null)
            return supplierRepository.findById(supplierId).map(Supplier::getName).orElse(null);
        return fallback;
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private String buildReason(String batchCode, String supplierName) {
        if (supplierName != null && !supplierName.isBlank())
            return batchCode + "-" + supplierName;
        return batchCode;
    }

    private Long effectiveExpiry(Long existing, Long incoming) {
        if (incoming != null) return incoming;
        return existing;
    }

    private String urlsToJson(List<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(urls); }
        catch (Exception e) { return null; }
    }

    private List<String> jsonToUrls(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json,
                objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class)); }
        catch (Exception e) { return List.of(); }
    }
}