package com.nhatnam.server.dto.pos;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateProgramRequest {
    String name;
    Long qualifyFrom, qualifyTo, applyFrom, applyTo;
    BigDecimal minSpend, maxDiscountPerCustomer;
    List<OptionRequest> options;

    @Data public static class OptionRequest {
        String discountType;
        BigDecimal discountValue, maxPerUse;
        String label;
    }
}
