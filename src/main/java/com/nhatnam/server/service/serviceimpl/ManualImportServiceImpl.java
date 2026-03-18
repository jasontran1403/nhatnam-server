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

    private final IngredientRepository ingredientRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final ImportBatchSequenceRepository batchSeqRepository;

    // ── Sinh mã batch IS-YYYYMMDD-XXXXXXXXXX (10 chữ số, pessimistic lock) ──
    @Transactional
    protected String nextBatchCode() {
        String dateKey = "IS-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        ImportBatchSequence seq = batchSeqRepository
                .findByDateKeyForUpdate(dateKey)
                .orElse(ImportBatchSequence.builder().dateKey(dateKey).lastSeq(0L).build());

        long next = seq.getLastSeq() + 1;
        seq.setLastSeq(next);
        batchSeqRepository.save(seq);

        return String.format("%s-%010d", dateKey, next);
        // e.g. IS-20260307-0000000001
    }

    // ── Logic hạn dùng hiệu lực ──────────────────────────────────────────────
    /**
     * So sánh hạn mới nhập (newExpiry) với hạn cũ đang lưu (currentExpiry):
     * - Nếu hạn mới chưa hết VÀ (không có hạn cũ HOẶC hạn mới < hạn cũ)
     *   → dùng hạn mới (lô sẽ hết trước)
     * - Nếu hạn mới đã hết → giữ hạn cũ (nếu có)
     * - Nếu không có hạn nào → null
     *
     * Trả về epoch-millis của hạn hiệu lực (để lưu vào ingredient).
     */
    private Long effectiveExpiry(Long currentExpiryMs, Long newExpiryMs) {
        long now = System.currentTimeMillis();

        boolean newValid  = newExpiryMs  != null && newExpiryMs  > now;
        boolean oldValid  = currentExpiryMs != null && currentExpiryMs > now;

        if (newValid) {
            if (!oldValid) return newExpiryMs;                         // chỉ có hạn mới
            return newExpiryMs < currentExpiryMs ? newExpiryMs : currentExpiryMs; // lấy sớm hơn
        }
        // hạn mới đã hết hoặc null
        if (oldValid) return currentExpiryMs;                          // giữ hạn cũ
        // cả 2 đều hết/null → trả về hạn mới nếu có (dù đã hết)
        return newExpiryMs != null ? newExpiryMs : currentExpiryMs;
    }

    // ── Main: nhập batch ─────────────────────────────────────────────────────
    @Override
    @Transactional
    public ManualImportResponse importBatch(ManualImportRequest request, User actor) {

        String batchCode = nextBatchCode();
        long now = System.currentTimeMillis();

        List<ManualImportResponse.ImportItemResult> results = new ArrayList<>();

        for (ManualImportRequest.ImportItem item : request.getItems()) {

            Ingredient ing = ingredientRepository.findByIdAndIsActiveTrue(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException(
                            "Ingredient not found with ID: " + item.getIngredientId()));

            BigDecimal before = ing.getStockQuantity();
            BigDecimal added  = item.getQuantity();
            BigDecimal after  = before.add(added);    // cộng dồn

            // Cập nhật tồn kho
            ing.setStockQuantity(after);

            // Tính hạn dùng hiệu lực
            Long newExpiryMs  = item.getExpiryDate();
            Long effectiveMs  = effectiveExpiry(ing.getExpiryDate(), newExpiryMs);
            ing.setExpiryDate(effectiveMs);
            ing.setUpdatedAt(now);
            ingredientRepository.save(ing);

            // Ghi log
            // IS-20260307-0000000001
            InventoryLog logEntry = InventoryLog.builder()
                    .ingredient(ing)
                    .order(null)
                    .action(InventoryAction.IMPORT)
                    .quantity(added)
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .reason(batchCode)
                    .user(actor)
                    .createdAt(now)
                    .build();
            inventoryLogRepository.save(logEntry);

            log.info("[IMPORT] {} | {} +{} → {} | batch={}",
                    ing.getName(), before, added, after, batchCode);

            results.add(ManualImportResponse.ImportItemResult.builder()
                    .ingredientId(ing.getId())
                    .ingredientName(ing.getName())
                    .unit(ing.getUnit())
                    .quantityAdded(added)
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .effectiveExpiryDate(effectiveMs)
                    .logReason(batchCode)
                    .build());
        }

        log.info("[IMPORT] Batch {} completed: {} items by user {}",
                batchCode, results.size(), actor.getUsername());

        return ManualImportResponse.builder()
                .batchCode(batchCode)
                .totalItems(results.size())
                .items(results)
                .build();
    }
}