package com.nhatnam.server.dto.pos;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UpdatePosCategoryRequest {
    private String name;
    private String imageUrl;
    private Integer displayOrder;
    private Boolean isActive;
    private Boolean singlePrice;
}