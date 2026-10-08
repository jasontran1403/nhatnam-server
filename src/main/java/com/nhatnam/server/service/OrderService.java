package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.CreateOrderRequest;
import com.nhatnam.server.dto.response.OrderDetailResponse;
import com.nhatnam.server.dto.response.OrderListResponse;
import com.nhatnam.server.dto.response.OrderResponse;
import com.nhatnam.server.enumtype.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface OrderService {
    Page<OrderResponse> getSaleOrderHistoryAll(
            Long fromTs, Long toTs, String q, int page, int size);

    Page<OrderResponse> getSaleOrderHistory(
            Long userId,
            Long fromTs,
            Long toTs,
            String q,
            int page,
            int size
    );
    OrderResponse createOrder(CreateOrderRequest request, Long userId);

    OrderResponse getOrderById(Long id);

    OrderResponse getOrderByCode(String orderCode);

    List<OrderResponse> getMyOrders(Long userId);

    List<OrderResponse> getOrdersByStatus(OrderStatus status);

    OrderResponse updateOrderStatus(Long orderId, OrderStatus newStatus);

    OrderResponse cancelOrder(Long orderId, Long userId);

    /**
     * SUPER ADMIN hủy đơn hàng sỉ/lẻ:
     *  1) Cộng lại tồn kho.
     *  2) Cộng lại số lượng vào đúng lô giá vốn (FIFO).
     */
    OrderResponse cancelSaleOrder(Long orderId);

    Page<OrderListResponse> getOrders(
            String search,              // Tìm theo tên/sđt người đặt/nhận
            OrderStatus status,         // Filter trạng thái
            Pageable pageable
    );

    // Chi tiết đơn hàng
    OrderDetailResponse getOrderDetail(Long orderId);
}