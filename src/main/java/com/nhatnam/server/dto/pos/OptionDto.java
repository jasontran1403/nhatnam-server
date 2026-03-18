package com.nhatnam.server.dto.pos;

import com.nhatnam.server.entity.pos.PosDiscountOption;

import java.math.BigDecimal;

public record OptionDto(
        Long   id, String discountType,
        BigDecimal discountValue, BigDecimal maxPerUse,
        String label, boolean isItemType
) {
    static OptionDto from(PosDiscountOption o) {
        return new OptionDto(o.getId(), o.getDiscountType().name(),
                o.getDiscountValue(), o.getMaxPerUse(),
                o.getLabel(), o.isItemType());
    }
}