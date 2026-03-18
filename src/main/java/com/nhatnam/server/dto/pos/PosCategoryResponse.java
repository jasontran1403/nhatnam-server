package com.nhatnam.server.dto.pos;

import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosCategoryResponse {
    private Long id;
    private String name;
    private String imageUrl;
    private Integer displayOrder;
    private Boolean isActive;
    private Boolean singlePrice; // true = category Lạnh, chỉ 1 giá
    private Integer productCount;
}
