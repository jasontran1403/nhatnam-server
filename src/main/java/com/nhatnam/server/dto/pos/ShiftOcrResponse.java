// src/main/java/com/nhatnam/server/dto/pos/ShiftOcrResponse.java
package com.nhatnam.server.dto.pos;

import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ShiftOcrResponse {
    private Long   imageId;        // ID của PosShiftImage vừa lưu
    private String imagePath;      // đường dẫn ảnh
    private String ocrStatus;      // SUCCESS | FAILED | PENDING
    private String staffName;      // từ OCR
    private String date;           // "dd/MM/yyyy" từ OCR
    private String errorMessage;   // nếu OCR thất bại
    private List<OcrInventoryItem> inventoryList;
}