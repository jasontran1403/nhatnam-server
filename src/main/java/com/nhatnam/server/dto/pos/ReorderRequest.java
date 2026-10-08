package com.nhatnam.server.dto.pos;

import lombok.*;

import java.util.List;

/**
 * Body chung cho các endpoint cập nhật thứ tự (displayOrder) hàng loạt bằng
 * kéo-thả: nguyên liệu, sản phẩm, danh mục.
 * FE gửi: { "items": [ { "id": 1, "displayOrder": 0 }, ... ] }
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReorderRequest {

    private List<Item> items;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Item {
        private Long id;
        private Integer displayOrder;
    }
}
