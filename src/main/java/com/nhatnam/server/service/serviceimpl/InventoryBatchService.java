package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.request.ManualExportRequest;
import com.nhatnam.server.dto.request.ManualImportRequest;
import com.nhatnam.server.dto.request.StockCheckRequest;
import com.nhatnam.server.dto.response.InventoryBatchDetailResponse;
import com.nhatnam.server.dto.response.InventoryBatchSummaryResponse;
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

@Service
@RequiredArgsConstructor
@Log4j2
public class InventoryBatchService {

    private final InventoryBatchRepository     batchRepository;
    private final InventoryLogRepository       logRepository;
    private final IngredientRepository         ingredientRepository;
    private final ImportBatchSequenceRepository seqRepository;

    // ── Sequence ─────────────────────────────────────────────────────────────

    /**
     * Sinh batchCode theo prefix và ngày.
     * IS- / ES- / CS- + YYYYMMDD + 10 chữ số thứ tự
     */
    @Transactional
    public String nextBatchCode(String prefix) {
        String dateKey = prefix + LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        ImportBatchSequence seq = seqRepository
                .findByDateKeyForUpdate(dateKey)
                .orElse(ImportBatchSequence.builder()
                        .dateKey(dateKey).lastSeq(0L).build());

        long next = seq.getLastSeq() + 1;
        seq.setLastSeq(next);
        seqRepository.save(seq);

        return String.format("%s-%010d", dateKey, next);
        // e.g. IS-20260404-0000000001
    }

    // ── IMPORT (gọi từ ManualImportService) ──────────────────────────────────

    @Transactional
    public InventoryBatch createImportBatch(
            ManualImportRequest request, User actor, String receiptImageUrl) {

        String batchCode   = nextBatchCode("IS-");
        String supplierRef = request.getSupplierRef();
        String logReason   = buildReason(batchCode, supplierRef);
        long   now         = System.currentTimeMillis();

        InventoryBatch batch = InventoryBatch.builder()
                .batchCode(batchCode)
                .action(InventoryAction.IMPORT)
                .supplierRef(supplierRef)
                .receiptImageUrl(receiptImageUrl)
                .createdBy(actor)
                .createdAt(now)
                .build();
        batchRepository.save(batch);

        List<InventoryLog> logs = new ArrayList<>();

        for (ManualImportRequest.ImportItem item : request.getItems()) {
            Ingredient ing = ingredientRepository
                    .findByIdAndIsActiveTrue(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException(
                            "Ingredient not found: " + item.getIngredientId()));

            BigDecimal before = ing.getStockQuantity();
            BigDecimal added  = item.getQuantity();
            BigDecimal after  = before.add(added);

            ing.setStockQuantity(after);
            ing.setExpiryDate(effectiveExpiry(ing.getExpiryDate(), item.getExpiryDate()));
            ing.setUpdatedAt(now);
            ingredientRepository.save(ing);

            logs.add(InventoryLog.builder()
                    .ingredient(ing)
                    .batch(batch)
                    .action(InventoryAction.IMPORT)
                    .quantity(added)
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .reason(logReason)
                    .receiptImageUrl(receiptImageUrl)
                    .user(actor)
                    .createdAt(now)
                    .build());
        }

        logRepository.saveAll(logs);
        batch.setLogs(logs);
        return batch;
    }

    // ── EXPORT thủ công ───────────────────────────────────────────────────────

    @Transactional
    public InventoryBatch createExportBatch(ManualExportRequest request, User actor) {
        String batchCode = nextBatchCode("ES-");
        String reason    = request.getReason();
        String logReason = buildReason(batchCode, reason);
        long   now       = System.currentTimeMillis();

        InventoryBatch batch = InventoryBatch.builder()
                .batchCode(batchCode)
                .action(InventoryAction.EXPORT)
                .supplierRef(reason)
                .createdBy(actor)
                .createdAt(now)
                .build();
        batchRepository.save(batch);

        List<InventoryLog> logs = new ArrayList<>();

        for (ManualExportRequest.ExportItem item : request.getItems()) {
            Ingredient ing = ingredientRepository
                    .findByIdAndIsActiveTrue(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException(
                            "Ingredient not found: " + item.getIngredientId()));

            BigDecimal before = ing.getStockQuantity();
            BigDecimal qty    = item.getQuantity(); // dương
            BigDecimal after  = before.subtract(qty);

            if (after.compareTo(BigDecimal.ZERO) < 0)
                throw new RuntimeException(String.format(
                        "Không đủ tồn kho '%s' (còn: %s, cần: %s %s)",
                        ing.getName(), before, qty, ing.getUnit()));

            ing.setStockQuantity(after);
            ing.setUpdatedAt(now);
            ingredientRepository.save(ing);

            logs.add(InventoryLog.builder()
                    .ingredient(ing)
                    .batch(batch)
                    .action(InventoryAction.EXPORT)
                    .quantity(qty.negate()) // âm trong log
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .reason(logReason)
                    .user(actor)
                    .createdAt(now)
                    .build());
        }

        logRepository.saveAll(logs);
        batch.setLogs(logs);
        return batch;
    }

    // ── ADJUST (kiểm kho) ─────────────────────────────────────────────────────

    @Transactional
    public InventoryBatch createAdjustBatch(StockCheckRequest request, User actor) {
        String batchCode = nextBatchCode("CS-");
        long   now       = System.currentTimeMillis();

        InventoryBatch batch = InventoryBatch.builder()
                .batchCode(batchCode)
                .action(InventoryAction.ADJUST)
                .createdBy(actor)
                .createdAt(now)
                .build();
        batchRepository.save(batch);

        List<InventoryLog> logs = new ArrayList<>();

        for (StockCheckRequest.CheckItem item : request.getItems()) {
            Ingredient ing = ingredientRepository
                    .findByIdAndIsActiveTrue(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException(
                            "Ingredient not found: " + item.getIngredientId()));

            BigDecimal before = ing.getStockQuantity();
            BigDecimal actual = item.getActualQuantity();
            BigDecimal diff   = actual.subtract(before); // dương=dư, âm=thiếu, zero=khớp

            // Cập nhật tồn kho về số thực tế (kể cả diff=0, để track)
            if (diff.compareTo(BigDecimal.ZERO) != 0) {
                ing.setStockQuantity(actual);
                ing.setUpdatedAt(now);
                ingredientRepository.save(ing);
            }

            // Luôn ghi log để phiếu kiểm hiển thị đầy đủ (kể cả khớp)
            logs.add(InventoryLog.builder()
                    .ingredient(ing)
                    .batch(batch)
                    .action(InventoryAction.ADJUST)
                    .quantity(diff)         // 0 nếu khớp
                    .quantityBefore(before)
                    .quantityAfter(actual)
                    .reason(batchCode)
                    .user(actor)
                    .createdAt(now)
                    .build());
        }

        logRepository.saveAll(logs);
        batch.setLogs(logs);
        return batch;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public Page<InventoryBatchSummaryResponse> listBatches(
            String action, int page, int size) {

        PageRequest pr = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<InventoryBatch> batches = (action == null || action.isBlank())
                ? batchRepository.findAllByOrderByCreatedAtDesc(pr)
                : batchRepository.findByActionOrderByCreatedAtDesc(
                InventoryAction.valueOf(action.toUpperCase()), pr);

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
                .createdByName(b.getCreatedBy() != null ? b.getCreatedBy().getUsername() : "")
                .createdAt(b.getCreatedAt())
                .totalItems(b.getLogs() != null ? b.getLogs().size() : 0)
                .build();
    }

    private InventoryBatchDetailResponse toDetail(InventoryBatch b) {
        List<InventoryBatchDetailResponse.LogLineResponse> lines = b.getLogs().stream()
                .map(log -> {
                    String adjustStatus = null;
                    if (b.getAction() == InventoryAction.ADJUST) {
                        int cmp = log.getQuantity().compareTo(BigDecimal.ZERO);
                        adjustStatus = cmp == 0 ? "MATCH"
                                : cmp > 0      ? "SURPLUS"
                                : "SHORTAGE";
                    }
                    return InventoryBatchDetailResponse.LogLineResponse.builder()
                            .ingredientId(log.getIngredient().getId())
                            .ingredientName(log.getIngredient().getName())
                            .unit(log.getIngredient().getUnit())
                            .quantity(log.getQuantity())
                            .quantityBefore(log.getQuantityBefore())
                            .quantityAfter(log.getQuantityAfter())
                            .adjustStatus(adjustStatus)
                            .build();
                })
                .toList();

        return InventoryBatchDetailResponse.builder()
                .id(b.getId())
                .batchCode(b.getBatchCode())
                .action(b.getAction().name())
                .supplierRef(b.getSupplierRef())
                .receiptImageUrl(b.getReceiptImageUrl())
                .createdByName(b.getCreatedBy() != null ? b.getCreatedBy().getUsername() : "")
                .createdAt(b.getCreatedAt())
                .lines(lines)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildReason(String batchCode, String ref) {
        return (ref != null && !ref.isBlank())
                ? batchCode + " | NCC: " + ref.trim()
                : batchCode;
    }

    private Long effectiveExpiry(Long current, Long incoming) {
        long now = System.currentTimeMillis();
        boolean newOk = incoming != null && incoming > now;
        boolean oldOk = current  != null && current  > now;
        if (newOk) return (!oldOk || incoming < current) ? incoming : current;
        return oldOk ? current : (incoming != null ? incoming : current);
    }
}