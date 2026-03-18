package com.nhatnam.server.dto.pos;

import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosVariantResponse {
    private Long id;
    private String groupName;
    private Integer minSelect;
    private Integer maxSelect;
    private Boolean allowRepeat;
    private Integer displayOrder;
    private Boolean isActive;
    private Boolean isAddonGroup;
    private Boolean isDefault;           // ← NEW
    private List<PosVariantIngredientResponse> ingredients;
}