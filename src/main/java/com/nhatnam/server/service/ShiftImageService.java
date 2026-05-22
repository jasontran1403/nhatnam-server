// src/main/java/com/nhatnam/server/service/ShiftImageService.java
package com.nhatnam.server.service;

import com.nhatnam.server.dto.pos.OcrInventoryItem;
import com.nhatnam.server.dto.pos.ShiftOcrResponse;
import com.nhatnam.server.entity.pos.PosIngredient;
import com.nhatnam.server.entity.pos.PosShiftImage;
import com.nhatnam.server.repository.pos.PosIngredientRepository;
import com.nhatnam.server.repository.pos.PosShiftImageRepository;
import com.nhatnam.server.repository.pos.PosShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class ShiftImageService {

    private final PosIngredientRepository ingredientRepository;
    private final PosShiftImageRepository shiftImageRepository;
    private final PosShiftRepository      shiftRepository;
    private final PythonOcrService        pythonOcrService;
    private final FileStorageService      storageService;

    // ── Entry point: nhiều ảnh ────────────────────────────────────────────────

    /**
     * Xử lý danh sách ảnh upload từ Flutter.
     *
     * Với mỗi ảnh:
     *   - Lưu file vào storage
     *   - Lưu record PosShiftImage (PENDING)
     *   - Gọi Python OCR
     *   - Cập nhật status SUCCESS/FAILED
     *
     * Sau đó merge tất cả raw items → match với DB ingredient → trả về.
     *
     * Merge rule:
     *   - Nếu cùng ingredientId xuất hiện ở nhiều ảnh → cộng dồn số lượng
     *   - Ảnh xử lý theo thứ tự index
     */
    public ShiftOcrResponse processShiftImages(
            List<MultipartFile> files,
            String type,
            Long shiftId,
            Long storeId) {

        if (files == null || files.isEmpty()) {
            return buildFailResponse(null, "", "Không có ảnh nào được gửi lên");
        }

        log.info("[OCR] Processing {} image(s), type={}, shiftId={}, storeId={}",
                files.size(), type, shiftId, storeId);

        // Load ingredients 1 lần dùng cho tất cả ảnh
        List<PosIngredient> storeIngredients =
                ingredientRepository.findByStoreIdAndIsActive(storeId, true);

        // staffName, date — lấy từ ảnh đầu tiên đọc được
        String mergedStaffName = null;
        String mergedDate      = null;

        // Map ingredientId → OcrInventoryItem (để merge số lượng)
        // Key = matchedIngredientId (int)
        Map<Integer, OcrInventoryItem> mergedMap = new LinkedHashMap<>();

        // Xử lý từng ảnh
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            log.info("[OCR] Image {}/{}: {}", i + 1, files.size(),
                    file.getOriginalFilename());

            // 1. Lưu ảnh
            String savedPath = "";
            Long   imageId   = null;
            try {
                byte[] bytes    = file.getBytes();
                String filename = buildFilename(type, storeId, i, file.getOriginalFilename());
                savedPath = storageService.saveShiftImage(bytes, filename);

                PosShiftImage.PosShiftImageBuilder builder = PosShiftImage.builder()
                        .storeId(storeId)
                        .imagePath(savedPath)
                        .imageType(type)
                        .ocrStatus("PENDING")
                        .createdAt(System.currentTimeMillis());

                if (shiftId != null) {
                    shiftRepository.findById(shiftId).ifPresent(builder::shift);
                }

                PosShiftImage img = shiftImageRepository.save(builder.build());
                imageId = img.getId();
            } catch (Exception e) {
                log.warn("[OCR] Cannot save image {}: {}", i + 1, e.getMessage());
            }

            // 2. Gọi Python OCR
            byte[] imageBytes;
            try {
                imageBytes = file.getBytes();
            } catch (Exception e) {
                updateImageStatus(imageId, "FAILED", "Cannot read bytes: " + e.getMessage());
                log.warn("[OCR] Skip image {} — cannot read bytes", i + 1);
                continue;
            }

            PythonOcrService.OcrResult ocrResult =
                    pythonOcrService.callOcr(imageBytes, file.getOriginalFilename());

            if (!ocrResult.success()) {
                updateImageStatus(imageId, "FAILED", ocrResult.errorMessage());
                log.warn("[OCR] Image {} OCR failed: {}", i + 1, ocrResult.errorMessage());
                continue;
            }

            updateImageStatus(imageId, "SUCCESS", null);

            // 3. Lấy staffName, date từ ảnh đầu tiên thành công
            if (mergedStaffName == null && ocrResult.staffName() != null) {
                mergedStaffName = ocrResult.staffName();
            }
            if (mergedDate == null && ocrResult.date() != null) {
                mergedDate = ocrResult.date();
            }

            // 4. Match + merge vào mergedMap
            List<OcrInventoryItem> matchedItems =
                    matchIngredients(ocrResult.inventoryList(), storeIngredients);

            for (OcrInventoryItem item : matchedItems) {
                Integer ingId = item.getMatchedIngredientId();
                if (ingId == null) continue;

                if (mergedMap.containsKey(ingId)) {
                    // Cộng dồn số lượng từ các ảnh khác nhau
                    OcrInventoryItem existing = mergedMap.get(ingId);
                    mergedMap.put(ingId, OcrInventoryItem.builder()
                            .name(existing.getName())
                            .matchedIngredientId(ingId)
                            .matchedIngredientName(existing.getMatchedIngredientName())
                            .packQuantity(
                                    sumNullable(existing.getPackQuantity(), item.getPackQuantity()))
                            .unitQuantity(
                                    sumNullableDouble(existing.getUnitQuantity(), item.getUnitQuantity()))
                            .build());
                    log.debug("[OCR-MERGE] Merge '{}': pack={}, unit={}",
                            existing.getMatchedIngredientName(),
                            mergedMap.get(ingId).getPackQuantity(),
                            mergedMap.get(ingId).getUnitQuantity());
                } else {
                    mergedMap.put(ingId, item);
                }
            }

            log.info("[OCR] Image {}/{} done: {} items matched",
                    i + 1, files.size(), matchedItems.size());
        }

        List<OcrInventoryItem> finalList = new ArrayList<>(mergedMap.values());

        log.info("[OCR] All images done: {} unique ingredients merged", finalList.size());

        // Trả về FAILED nếu không có item nào
        if (finalList.isEmpty()) {
            return ShiftOcrResponse.builder()
                    .ocrStatus("FAILED")
                    .errorMessage("Không nhận dạng được nguyên liệu nào từ "
                            + files.size() + " ảnh")
                    .inventoryList(Collections.emptyList())
                    .build();
        }

        return ShiftOcrResponse.builder()
                .ocrStatus("SUCCESS")
                .staffName(mergedStaffName)
                .date(mergedDate)
                .inventoryList(finalList)
                .build();
    }

    // ── Entry point: 1 ảnh (backward compat) ─────────────────────────────────

    public ShiftOcrResponse processShiftImage(
            MultipartFile file,
            String type,
            Long shiftId,
            Long storeId) {
        return processShiftImages(List.of(file), type, shiftId, storeId);
    }

    // ── Matching logic ────────────────────────────────────────────────────────

    private List<OcrInventoryItem> matchIngredients(
            List<com.nhatnam.server.dto.pos.OcrInventoryItem> rawItems,
            List<PosIngredient> storeIngredients) {

        Map<String, PosIngredient> exactMap = storeIngredients.stream()
                .collect(Collectors.toMap(
                        i -> i.getName().trim().toLowerCase(),
                        i -> i,
                        (a, b) -> a));

        List<OcrInventoryItem> result = new ArrayList<>();

        for (com.nhatnam.server.dto.pos.OcrInventoryItem raw : rawItems) {
            if (raw.getName() == null || raw.getName().isBlank()) continue;

            String        ocrName = raw.getName().trim();
            PosIngredient matched = findBestMatch(ocrName, exactMap, storeIngredients);

            if (matched == null) {
                log.debug("[OCR-MATCH] No match for: '{}'", ocrName);
                continue;
            }

            log.info("[OCR-MATCH] '{}' → '{}' (id={})",
                    ocrName, matched.getName(), matched.getId());

            result.add(OcrInventoryItem.builder()
                    .name(ocrName)
                    .packQuantity(raw.getPackQuantity())
                    .unitQuantity(raw.getUnitQuantity())
                    .matchedIngredientId((int) (long) matched.getId())
                    .matchedIngredientName(matched.getName())
                    .build());
        }

        return result;
    }

    private PosIngredient findBestMatch(
            String ocrName,
            Map<String, PosIngredient> exactMap,
            List<PosIngredient> allIngredients) {

        String ocrLower = ocrName.toLowerCase();

        // 1. Exact match
        PosIngredient exact = exactMap.get(ocrLower);
        if (exact != null) return exact;

        // 2. DB name contains OCR name
        if (ocrLower.length() >= 4) {
            List<PosIngredient> hits = allIngredients.stream()
                    .filter(i -> i.getName().toLowerCase().contains(ocrLower))
                    .collect(Collectors.toList());
            if (hits.size() == 1) return hits.get(0);
        }

        // 3. OCR name contains DB name
        List<PosIngredient> reverseHits = allIngredients.stream()
                .filter(i -> {
                    String dbLower = i.getName().toLowerCase();
                    return dbLower.length() >= 4 && ocrLower.contains(dbLower);
                })
                .collect(Collectors.toList());
        if (reverseHits.size() == 1) return reverseHits.get(0);

        // 4. Fuzzy token match
        String[] tokens = ocrLower.split("\\s+");
        List<String> important = Arrays.stream(tokens)
                .filter(t -> t.length() >= 3)
                .collect(Collectors.toList());

        if (!important.isEmpty()) {
            List<PosIngredient> fuzzy = allIngredients.stream()
                    .filter(i -> {
                        String dbLower = i.getName().toLowerCase();
                        return important.stream().allMatch(dbLower::contains);
                    })
                    .collect(Collectors.toList());

            if (fuzzy.size() == 1) return fuzzy.get(0);
            if (fuzzy.size() > 1) {
                return fuzzy.stream()
                        .min(Comparator.comparingInt(i -> i.getName().length()))
                        .orElse(null);
            }
        }

        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildFilename(String type, Long storeId, int index, String original) {
        String ext = (original != null && original.contains("."))
                ? original.substring(original.lastIndexOf('.'))
                : ".jpg";
        return String.format("shift_%s_%d_%d_img%d%s",
                type.toLowerCase(), storeId, System.currentTimeMillis(), index, ext);
    }

    /** Cộng 2 Integer nullable (null được coi là 0) */
    private Integer sumNullable(Integer a, Integer b) {
        int va = (a != null) ? a : 0;
        int vb = (b != null) ? b : 0;
        return va + vb;
    }

    /** Cộng 2 Double nullable (null được coi là 0.0) */
    private Double sumNullableDouble(Double a, Double b) {
        double va = (a != null) ? a : 0.0;
        double vb = (b != null) ? b : 0.0;
        // Làm tròn 2 chữ số thập phân để tránh floating point error
        return Math.round((va + vb) * 100.0) / 100.0;
    }

    private ShiftOcrResponse buildFailResponse(Long imageId, String path, String errMsg) {
        return ShiftOcrResponse.builder()
                .imageId(imageId)
                .imagePath(path != null ? path : "")
                .ocrStatus("FAILED")
                .errorMessage(errMsg)
                .inventoryList(Collections.emptyList())
                .build();
    }

    private void updateImageStatus(Long imageId, String status, String errorMsg) {
        if (imageId == null) return;
        try {
            shiftImageRepository.findById(imageId).ifPresent(img -> {
                img.setOcrStatus(status);
                img.setOcrError(errorMsg);
                shiftImageRepository.save(img);
            });
        } catch (Exception e) {
            log.warn("[OCR] Cannot update image status: {}", e.getMessage());
        }
    }
}