// src/main/java/com/nhatnam/server/dto/pos/OcrInventoryItem.java
package com.nhatnam.server.dto.pos;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OcrInventoryItem {
    private String  name;          // Tên nguyên liệu (raw từ OCR)
    private Integer packQuantity;  // Số bịch (null nếu không có)
    private Double  unitQuantity;  // Số lẻ (null nếu không có)
    private Integer matchedIngredientId;   // ID đã match với DB (null nếu không match)
    private String  matchedIngredientName; // Tên sau match
}