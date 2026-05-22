package com.nhatnam.server.service;

import com.nhatnam.server.dto.pos.PosCustomerOrderDto;
import com.nhatnam.server.entity.pos.PosOrder;
import com.nhatnam.server.entity.pos.PosOrderItem;
import com.nhatnam.server.entity.pos.PosOrderItemIngredient;
import com.nhatnam.server.repository.pos.PosOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PosCustomerOrderService {

    private final PosOrderRepository orderRepo;

    public PosCustomerOrderDto.PageResult getOrdersByCustomer(
            Long storeId, String phone, int page, int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<PosOrder> pg = orderRepo.findByStoreIdAndCustomerPhone(storeId, phone, pageable);

        List<PosCustomerOrderDto.OrderSummary> content = pg.getContent()
                .stream()
                .map(o -> new PosCustomerOrderDto.OrderSummary(
                        o.getId(),
                        o.getOrderCode(),
                        o.getOrderSource().name(),
                        o.getCreatedAt(),
                        o.getItems().stream().mapToInt(PosOrderItem::getQuantity).sum(),
                        o.getTotalAmount(),
                        o.getDiscountAmount(),
                        o.getFinalAmount(),
                        o.getPlatformFeeAmount(),
                        o.getPaymentMethod(),
                        o.getStatus().name()
                ))
                .toList();

        return new PosCustomerOrderDto.PageResult(
                content, page, size,
                pg.getTotalElements(),
                pg.getTotalPages(),
                pg.hasNext()
        );
    }

    public PosCustomerOrderDto.OrderDetail getOrderDetail(Long storeId, Long orderId) {
        PosOrder o = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn"));

        if (!o.getStore().getId().equals(storeId))
            throw new RuntimeException("Không có quyền truy cập");

        List<PosCustomerOrderDto.OrderItemDto> items = o.getItems()
                .stream()
                .map(i -> {
                    // SỬ DỤNG selectedIngredients thay vì getIngredients()
                    List<PosCustomerOrderDto.AddonDto> addons =
                            (i.getSelectedIngredients() != null
                                    ? i.getSelectedIngredients().stream()
                                    : java.util.stream.Stream.<PosOrderItemIngredient>empty())
                                    .filter(ing -> ing.getAddonPriceSnapshot() != null
                                            && ing.getAddonPriceSnapshot().compareTo(BigDecimal.ZERO) > 0)
                                    .map(ing -> new PosCustomerOrderDto.AddonDto(
                                            ing.getIngredientName(),
                                            ing.getSelectedCount() != null ? ing.getSelectedCount() : 1,
                                            ing.getAddonPriceSnapshot()
                                    ))
                                    .collect(Collectors.toList());

                    return new PosCustomerOrderDto.OrderItemDto(
                            i.getProductId(),
                            i.getProductName(),
                            i.getCategoryName(),
                            i.getQuantity(),
                            i.getBasePrice(),
                            i.getFinalUnitPrice(),
                            i.getSubtotal(),
                            i.getDiscountPercent(),
                            i.getVatPercent(),
                            i.getVatAmount(),
                            i.getNote(),
                            addons
                    );
                })
                .collect(Collectors.toList());

        boolean isAppOrder = o.getPlatformFeeAmount() != null
                && o.getPlatformFeeAmount().compareTo(BigDecimal.ZERO) > 0;

        BigDecimal totalAmount = o.getTotalAmount() != null ? o.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal discountAmount = o.getDiscountAmount() != null
                ? o.getDiscountAmount() : BigDecimal.ZERO;

        BigDecimal displayFinalAmount = totalAmount.subtract(discountAmount);

        return new PosCustomerOrderDto.OrderDetail(
                o.getId(),
                o.getOrderCode(),
                o.getAppOrderCode(),
                o.getOrderSource().name(),
                o.getCreatedAt(),
                o.getPaymentMethod(),
                o.getStatus().name(),
                o.getNote(),
                items,
                totalAmount,
                discountAmount,
                o.getDiscountNote(),
                o.getEVoucherCode(),
                o.getEVoucherDiscountAmount(),
                o.getTotalVatAmount(),
                o.getPlatformFeeAmount(),
                displayFinalAmount,
                isAppOrder,
                o.getPlatformRate()
        );
    }
}