package com.nhatnam.server.dto.pos;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftDenominationResponse {
    private Integer denomination;
    private Integer quantity;
    private Long total; // denomination * quantity
}
