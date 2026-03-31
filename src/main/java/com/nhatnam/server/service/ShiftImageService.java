// src/main/java/com/nhatnam/server/service/ShiftImageService.java
package com.nhatnam.server.service;

import com.nhatnam.server.dto.pos.OcrInventoryItem;
import com.nhatnam.server.dto.pos.ShiftOcrResponse;
import com.nhatnam.server.entity.pos.PosIngredient;
import com.nhatnam.server.entity.pos.PosShift;
import com.nhatnam.server.entity.pos.PosShiftImage;
import com.nhatnam.server.repository.pos.PosIngredientRepository;
import com.nhatnam.server.repository.pos.PosShiftImageRepository;
import com.nhatnam.server.repository.pos.PosShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Log4j2
public class ShiftImageService {

    private final PosShiftImageRepository shiftImageRepo;
    private final PosShiftRepository      shiftRepo;
    private final PosIngredientRepository ingredientRepo;
    private final PythonOcrService        pythonOcrService;
    private final FileStorageService      fileStorageService; // ← lưu + resize file

    /**
     * Upload ảnh kho → lưu disk (qua FileStorageService.saveShiftImage)
     * → gọi Python OCR → match ingredients → trả về ShiftOcrResponse.
     */
    public ShiftOcrResponse processShiftImage(
            MultipartFile file,
            String imageType,   // "OPEN" | "CLOSE"
            Long shiftId,
            Long storeId) throws IOException {

        // ── 1. Build tên file ────────────────────────────────────
        String now       = java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String shiftPart = shiftId != null ? shiftId.toString() : "preopen";
        // vd: "360_20260331_183045_OPEN.png"
        String filename  = shiftPart + "_" + now + "_" + imageType + ".png";

        // ── 2. Lưu file qua FileStorageService ──────────────────
        // resize về max 1920px, ghi vào nhatnam-storage/shift-images/
        byte[] imageBytes = file.getBytes();
        String imagePath  = fileStorageService.saveShiftImage(imageBytes, filename);
        // → "/images/shift-images/360_20260331_183045_OPEN.png"

        // ── 3. Lưu entity với status PENDING ────────────────────
        PosShift shift = null;
        if (shiftId != null) {
            shift = shiftRepo.findById(shiftId).orElse(null);
        }

        String actionTime = LocalTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        PosShiftImage entity = PosShiftImage.builder()
                .shift(shift)
                .imageType(imageType)
                .imagePath(imagePath)
                .ocrStatus("PENDING")
                .createdAt(System.currentTimeMillis())
                .actionTime(actionTime)
                .storeId(storeId)
                .build();
        entity = shiftImageRepo.save(entity);

        // ── 4. Gọi Python OCR ────────────────────────────────────
        PythonOcrService.OcrResult ocr = pythonOcrService.callOcr(imageBytes, filename);

        // ── 5. Match tên ingredient với DB ──────────────────────
        List<OcrInventoryItem> matched = List.of();
        if (ocr.success() && ocr.inventoryList() != null && !ocr.inventoryList().isEmpty()) {
            matched = matchIngredients(ocr.inventoryList(), storeId);
        }

        // ── 6. Update entity với kết quả OCR ────────────────────
        entity.setOcrStaffName(ocr.staffName());
        entity.setOcrDate(ocr.date());
        entity.setOcrRawJson(ocr.rawJson());
        entity.setOcrStatus(ocr.success() ? "SUCCESS" : "FAILED");
        entity.setOcrError(ocr.errorMessage());
        shiftImageRepo.save(entity);

        log.info("[ShiftOCR] imageId={} path={} type={} status={} staffName={} date={} items={}",
                entity.getId(), imagePath, imageType,
                entity.getOcrStatus(), ocr.staffName(), ocr.date(), matched.size());

        // ── 7. Build + return response ───────────────────────────
        return ShiftOcrResponse.builder()
                .imageId(entity.getId())
                .imagePath(imagePath)
                .ocrStatus(entity.getOcrStatus())
                .staffName(ocr.staffName())
                .date(ocr.date())
                .errorMessage(ocr.errorMessage())
                .inventoryList(matched)
                .build();
    }

    private List<OcrInventoryItem> matchIngredients(
            List<OcrInventoryItem> ocrItems, Long storeId) {

        List<PosIngredient> allIngs = ingredientRepo
                .findByStoreIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(storeId);

        List<OcrInventoryItem> result = new ArrayList<>();
        for (OcrInventoryItem item : ocrItems) {
            String ocrName = item.getName();
            if (ocrName == null || ocrName.isBlank()) continue;

            PosIngredient best = findBestMatch(ocrName.trim(), allIngs);

            result.add(OcrInventoryItem.builder()
                    .name(ocrName)
                    .packQuantity(item.getPackQuantity())
                    .unitQuantity(item.getUnitQuantity())
                    .matchedIngredientId(best != null ? best.getId().intValue() : null)
                    .matchedIngredientName(best != null ? best.getName() : null)
                    .build());
        }
        return result;
    }

    private PosIngredient findBestMatch(String ocrName, List<PosIngredient> ings) {
        String lower = ocrName.toLowerCase();
        // 1. Exact match
        for (PosIngredient ing : ings)
            if (ing.getName().toLowerCase().equals(lower)) return ing;
        // 2. Contains (cả 2 chiều)
        for (PosIngredient ing : ings) {
            String ingLower = ing.getName().toLowerCase();
            if (ingLower.contains(lower) || lower.contains(ingLower)) return ing;
        }
        return null;
    }
}