package com.nhatnam.server.dto.pos;

import com.nhatnam.server.enumtype.OrderSource;
import com.nhatnam.server.enumtype.PosOrderStatus;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosOrderResponse {
    private Long id;
    private String orderCode;
    private Long shiftId;
    private String staffName;
    private OrderSource orderSource;
    private PosOrderStatus status;
    private String paymentMethod;

    private BigDecimal totalAmount;
    private BigDecimal finalAmount;
    private BigDecimal discountAmount;
    private BigDecimal totalVat;
    private String note;

    private Long createdAt;
    private Long updatedAt;

    private List<PosOrderItemResponse> items;
}
