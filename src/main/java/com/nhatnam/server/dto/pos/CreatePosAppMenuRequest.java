package com.nhatnam.server.dto.pos;

import com.nhatnam.server.enumtype.AppPlatform;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CreatePosAppMenuRequest {
    @NotNull
    private Long productId;
    @NotNull
    private AppPlatform platform;
    @NotNull
    @DecimalMin("0.0")
    private BigDecimal price;
}
