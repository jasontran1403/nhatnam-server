package com.nhatnam.server.dto.pos;

import com.nhatnam.server.enumtype.AppPlatform;
import lombok.*;
import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosAppMenuResponse {
    private Long id;
    private AppPlatform platform;
    private BigDecimal price;
    private Boolean isActive;
}
