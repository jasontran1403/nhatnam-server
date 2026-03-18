package com.nhatnam.server.dto.pos;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CreatePosCategoryRequest {
    @NotBlank(message = "Tên category không được trống")
    private String name;
    private String imageUrl;
    private Integer displayOrder;
    private Boolean singlePrice; // true = category Lạnh
}