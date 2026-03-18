package com.nhatnam.server.dto.pos;

import lombok.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CreatePosVariantRequest {

    @NotNull
    private Long productId;

    @NotNull
    private String groupName;

    @NotNull
    private Integer minSelect;

    @NotNull
    private Integer maxSelect;

    private Boolean allowRepeat;
    private Boolean isAddonGroup;
    private Boolean isDefault;           // ← NEW
    private Integer displayOrder;
    private Boolean isActive;

    @NotNull
    private List<IngredientItem> ingredients;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class IngredientItem {
        @NotNull
        private Long ingredientId;
        private BigDecimal stockDeductPerUnit;
        private Integer maxSelectableCount;
        private BigDecimal addonPrice;
        private Integer displayOrder;
        private String subGroupTag;
        private Integer subGroupMaxSelect;
    }
}