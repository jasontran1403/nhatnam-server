package com.nhatnam.server.dto.pos;

import com.nhatnam.server.entity.pos.PosDiscountProgram;

import java.math.BigDecimal;
import java.util.List;

public record ProgramDto(
        Long id, String name, String status,
        Long qualifyFrom, Long qualifyTo,
        Long applyFrom, Long applyTo,
        BigDecimal minSpend, BigDecimal maxDiscountPerCustomer,
        List<OptionDto> options
) {
    static ProgramDto from(PosDiscountProgram p) {
        return new ProgramDto(p.getId(), p.getName(), p.getStatus().name(),
                p.getQualifyFrom(), p.getQualifyTo(),
                p.getApplyFrom(), p.getApplyTo(),
                p.getMinSpend(), p.getMaxDiscountPerCustomer(),
                p.getOptions().stream().map(OptionDto::from).toList());
    }
}