package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.request.ManualImportRequest;
import com.nhatnam.server.dto.response.ManualImportResponse;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.InventoryAction;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.ManualImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
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
public class ManualImportServiceImpl implements ManualImportService {

    private final IngredientRepository          ingredientRepository;
    private final InventoryLogRepository         inventoryLogRepository;
    private final ImportBatchSequenceRepository  batchSeqRepository;

    // ── Sinh mã batch IS-YYYYMMDD-XXXXXXXXXX ─────────────────────────────────
    @Transactional
    protected String nextBatchCode() {
        String dateKey = "IS-" + LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        ImportBatchSequence seq = batchSeqRepository
                .findByDateKeyForUpdate(dateKey)
                .orElse(ImportBatchSequence.builder()
                        .dateKey(dateKey).lastSeq(0L).build());

        long next = seq.getLastSeq() + 1;
        seq.setLastSeq(next);
        batchSeqRepository.save(seq);

        return String.format("%s-%010d", dateKey, next);
        // e.g. IS-20260307-0000000001
    }

    // ── Logic hạn dùng hiệu lực ──────────────────────────────────────────────
    private Long effectiveExpiry(Long currentExpiryMs, Long newExpiryMs) {
        long now = System.currentTimeMillis();

        boolean newValid = newExpiryMs     != null && newExpiryMs     > now;
        boolean oldValid = currentExpiryMs != null && currentExpiryMs > now;

        if (newValid) {
            if (!oldValid) return newExpiryMs;
            return newExpiryMs < currentExpiryMs ? newExpiryMs : currentExpiryMs;
        }
        if (oldValid)         return currentExpiryMs;
        return newExpiryMs != null ? newExpiryMs : currentExpiryMs;
    }

    // ── Build log reason: batchCode + supplierRef (nếu có) ───────────────────
    private String buildReason(String batchCode, String supplierRef) {
        if (supplierRef != null && !supplierRef.isBlank()) {
            return batchCode + " | NCC: " + supplierRef.trim();
        }
        return batchCode;
    }

    // ── Main: nhập batch ─────────────────────────────────────────────────────
    @Override
    @Transactional
    public ManualImportResponse importBatch(ManualImportRequest request, User actor) {

        String batchCode    = nextBatchCode();
        String supplierRef  = request.getSupplierRef();
        String receiptImage = request.getReceiptImageUrl();
        String logReason    = buildReason(batchCode, supplierRef);
        long   now          = System.currentTimeMillis();

        List<ManualImportResponse.ImportItemResult> results = new ArrayList<>();

        for (ManualImportRequest.ImportItem item : request.getItems()) {

            Ingredient ing = ingredientRepository
                    .findByIdAndIsActiveTrue(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException(
                            "Ingredient not found with ID: " + item.getIngredientId()));

            BigDecimal before = ing.getStockQuantity();
            BigDecimal added  = item.getQuantity();
            BigDecimal after  = before.add(added);

            // Cập nhật tồn kho
            ing.setStockQuantity(after);

            // Tính hạn dùng hiệu lực
            Long effectiveMs = effectiveExpiry(ing.getExpiryDate(), item.getExpiryDate());
            ing.setExpiryDate(effectiveMs);
            ing.setUpdatedAt(now);
            ingredientRepository.save(ing);

            // Ghi log — reason chứa batchCode + supplierRef (nếu có)
            InventoryLog logEntry = InventoryLog.builder()
                    .ingredient(ing)
                    .order(null)
                    .action(InventoryAction.IMPORT)
                    .quantity(added)
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .reason(logReason)
                    .receiptImageUrl(receiptImage)   // ← THÊM
                    .user(actor)
                    .createdAt(now)
                    .build();
            inventoryLogRepository.save(logEntry);



            results.add(ManualImportResponse.ImportItemResult.builder()
                    .ingredientId(ing.getId())
                    .ingredientName(ing.getName())
                    .unit(ing.getUnit())
                    .quantityAdded(added)
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .effectiveExpiryDate(effectiveMs)
                    .logReason(logReason)
                    .build());
        }



        return ManualImportResponse.builder()
                .batchCode(batchCode)
                .totalItems(results.size())
                .supplierRef(supplierRef)            // ← trả về cho client
                .receiptImageUrl(receiptImage)       // ← trả về cho client
                .items(results)
                .build();
    }
}