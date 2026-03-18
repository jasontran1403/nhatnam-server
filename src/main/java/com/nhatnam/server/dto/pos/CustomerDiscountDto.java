package com.nhatnam.server.dto.pos;

import com.nhatnam.server.entity.pos.PosCustomerDiscount;

import java.math.BigDecimal;
import java.util.List;

public record CustomerDiscountDto(
        Long   id,
        Long   programId,
        String programName,
        String applyFrom,      // formatted
        String applyTo,
        BigDecimal maxDiscount,
        BigDecimal budgetUsed,
        BigDecimal budgetRemaining,
        boolean exhausted,
        Long   selectedOptionId,
        List<OptionDto> options
) {
    static CustomerDiscountDto from(PosCustomerDiscount cd) {
        BigDecimal max  = cd.getProgram().getMaxDiscountPerCustomer();
        BigDecimal used = cd.getBudgetUsed();
        return new CustomerDiscountDto(
                cd.getId(),
                cd.getProgram().getId(),
                cd.getProgram().getName(),
                fmtMs(cd.getProgram().getApplyFrom()),
                fmtMs(cd.getProgram().getApplyTo()),
                max, used, max.subtract(used),
                used.compareTo(max) >= 0,
                cd.getSelectedOption() != null ? cd.getSelectedOption().getId() : null,
                cd.getProgram().getOptions().stream().map(OptionDto::from).toList()
        );
    }
    static String fmtMs(Long ms) {
        return ms == null ? "" : java.time.ZonedDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(ms),
                        java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }
}