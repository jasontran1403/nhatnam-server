package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.config.TransactionLockManager;
import com.nhatnam.server.dto.request.CreateOrderRequest;
import com.nhatnam.server.dto.response.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.InventoryAction;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.enumtype.VatRate;
import com.nhatnam.server.exception.PriceChangedException;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.IngredientService;
import com.nhatnam.server.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository               orderRepository;
    private final ProductRepository             productRepository;
    private final IngredientRepository          ingredientRepository;
    private final ProductVariantRepository      variantRepository;
    private final ProductPriceTierRepository    priceTierRepository;
    private final UserRepository                userRepository;
    private final IngredientService             ingredientService;
    private final TransactionLockManager        transactionLockManager;
    private final VariantIngredientRepository   variantIngredientRepository;
    private final ProductIngredientRepository   productIngredientRepository;
    private final CustomerRepository            customerRepository;
    private final InventoryLogRepository        inventoryLogRepository;
    private final SellerWarehouseRepository      sellerWarehouseRepository;
    private final WarehouseIngredientStockRepository warehouseStockRepository;
    private final InventoryCostLotService        costLotService;

    @Override
    public Page<OrderResponse> getSaleOrderHistoryAll(
            Long fromTs, Long toTs, String q, int page, int size) {
        String query   = (q == null || q.isBlank()) ? null : q.trim();
        Pageable paged = PageRequest.of(page, size);
        return orderRepository.searchAll(fromTs, toTs, query, paged)
                .map(this::mapToResponse);
    }

    @Override
    public Page<OrderResponse> getSaleOrderHistory(
            Long userId, Long fromTs, Long toTs,
            String q, int page, int size) {
        String query   = (q == null || q.isBlank()) ? null : q.trim();
        Pageable paged = PageRequest.of(page, size);
        return orderRepository
                .searchByUser(userId, fromTs, toTs, query, paged)
                .map(this::mapToResponse);
    }

    // ════════════════════════════════════════════════════════════════
    // ADMIN: danh sách đơn hàng
    // ════════════════════════════════════════════════════════════════
    @Override
    public Page<OrderListResponse> getOrders(String search, OrderStatus status, Pageable pageable) {
        return orderRepository.findAllWithItems(search, status, pageable)
                .map(order -> OrderListResponse.builder()
                        .id(order.getId())
                        .orderCode(order.getOrderCode())
                        .customerName(order.getCustomerName())
                        .customerPhone(order.getCustomerPhone())
                        .receiverName(order.getCustomerName())
                        .receiverPhone(order.getCustomerPhone())
                        .finalAmount(order.getTotalAmount())
                        .createdAt(order.getCreatedAt())
                        .status(order.getStatus().name())
                        .build());
    }

    // ════════════════════════════════════════════════════════════════
    // ADMIN: chi tiết đơn hàng
    // ════════════════════════════════════════════════════════════════
    @Override
    public OrderDetailResponse getOrderDetail(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));

        List<OrderItemDetail> items = order.getOrderItems().stream().map(item -> {
            List<IngredientSnapshot> ingredients = item.getOrderItemIngredients().stream()
                    .map(ii -> IngredientSnapshot.builder()
                            .ingredientId(ii.getIngredientId())
                            .ingredientName(ii.getIngredientName())
                            .ingredientImageUrl(ii.getIngredientImageUrl())
                            .quantityUsed(ii.getQuantityUsed())
                            .unit(ii.getUnit())
                            .costPrice(ii.getCostPrice())
                            .costAmount(ii.getCostAmount())
                            .lots(ii.getLots() == null ? java.util.List.of()
                                    : ii.getLots().stream()
                                    .map(l -> IngredientSnapshot.LotSnapshot.builder()
                                            .costLotId(l.getCostLotId())
                                            .sourceRef(l.getSourceRef())
                                            .unitCost(l.getUnitCost())
                                            .quantity(l.getQuantity())
                                            .build())
                                    .collect(Collectors.toList()))
                            .build())
                    .collect(Collectors.toList());

            return OrderItemDetail.builder()
                    .productId(item.getProductId())
                    .productName(item.getProductName())
                    .productImageUrl(item.getProductImageUrl())
                    .variantId(item.getVariantId())
                    .variantName(item.getVariantName())
                    .priceName(buildPriceLabel(item))
                    .unitPrice(item.getUnitPrice())
                    .quantity(item.getQuantity())
                    .subtotal(item.getSubtotal())
                    .unit(item.getUnit())
                    .ingredients(ingredients)
                    .build();
        }).collect(Collectors.toList());

        return OrderDetailResponse.builder()
                .id(order.getId())
                .orderCode(order.getOrderCode())
                .customerName(order.getCustomerName())
                .customerPhone(order.getCustomerPhone())
                .shippingAddress(order.getShippingAddress())
                .notes(order.getNotes())
                .totalAmount(order.getTotalAmount())
                .discountAmount(order.getDiscountAmount())
                .finalAmount(order.getFinalAmount())
                .status(order.getStatus().name())
                .paymentStatus(order.getPaymentStatus().name())
                .paymentMethod(order.getPaymentMethod())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .items(items)
                .build();
    }

    private String buildPriceLabel(OrderItem item) {
        if (item.getPriceMode() == null) return null;
        return switch (item.getPriceMode()) {
            case "TIER"             -> item.getTierName() != null ? item.getTierName() : "Khung giá";
            case "DISCOUNT_PERCENT" -> "Giảm " + item.getDiscountPercent() + "%";
            default                 -> "Giá gốc";
        };
    }

    // ════════════════════════════════════════════════════════════════
    // TẠO ĐƠN HÀNG
    // ════════════════════════════════════════════════════════════════
    @Override
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request, Long userId) {
        long now = System.currentTimeMillis();

        String companyPhone   = null;
        String companyAddress = null;

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        Customer customer = null;
        if (request.getCustomerId() != null)
            customer = customerRepository.findById(request.getCustomerId()).orElse(null);
        if (customer == null && request.getCustomerPhone() != null)
            customer = customerRepository.findByPhone(request.getCustomerPhone()).orElse(null);

        final Customer finalCustomer = customer;
        boolean isCompany = finalCustomer != null
                && finalCustomer.getCustomerType() == Customer.CustomerType.COMPANY;

        // ── Xử lý theo loại KH ───────────────────────────────────────
        String customerType;
        String customerName;
        String customerPhone;
        String customerEmail;
        String shippingAddress;
        String companyName     = null;
        String shortName       = null;
        String taxCode         = null;
        String contactName     = null;
        String deliveryAddress = null;
        int    discountRate;

        if (isCompany) {
            customerType    = "COMPANY";
            companyName     = finalCustomer.getCompanyName();
            shortName       = finalCustomer.getShortName();
            taxCode         = finalCustomer.getTaxCode();
            contactName     = finalCustomer.getContactName();
            deliveryAddress = firstNonBlank(
                    finalCustomer.getDeliveryAddress(),
                    finalCustomer.getAddress());
            // customerName = tên rút gọn hoặc tên công ty để hiển thị
            companyPhone   = finalCustomer.getCompanyPhone();
            companyAddress = finalCustomer.getCompanyAddress();

            customerName    = firstNonBlank(shortName, companyName, finalCustomer.getName());
            customerPhone   = firstNonBlank(request.getCustomerPhone(), finalCustomer.getPhone());
            customerEmail   = firstNonBlank(request.getCustomerEmail(), finalCustomer.getEmail());
            shippingAddress = firstNonBlank(request.getShippingAddress(), deliveryAddress);
            discountRate    = finalCustomer.getDiscountRate() > 0
                    ? finalCustomer.getDiscountRate()
                    : (request.getDiscountRate() != null ? request.getDiscountRate() : 0);
        } else {
            // Khách lẻ hoặc không có trong hệ thống
            customerType    = "RETAIL";
            customerName    = request.getCustomerName();
            customerPhone   = firstNonBlank(request.getCustomerPhone(),
                    finalCustomer != null ? finalCustomer.getPhone() : null);
            customerEmail   = firstNonBlank(request.getCustomerEmail(),
                    finalCustomer != null ? finalCustomer.getEmail() : null);
            shippingAddress = firstNonBlank(request.getShippingAddress(),
                    finalCustomer != null && !finalCustomer.getAddresses().isEmpty()
                            ? finalCustomer.getAddresses().get(0).getAddress() : null);
            discountRate    = (finalCustomer != null && finalCustomer.getDiscountRate() > 0)
                    ? finalCustomer.getDiscountRate()
                    : (request.getDiscountRate() != null ? request.getDiscountRate() : 0);
        }

        // Draft order
        String orderCode = generateOrderCode();
        Order order = Order.builder()
                .orderCode(orderCode)
                .user(user)
                .customer(finalCustomer)
                .customerName(customerName)
                .customerPhone(customerPhone)
                .customerEmail(customerEmail)
                .shippingAddress(shippingAddress)
                .discountRate(discountRate)
                .type(request.getType())
                .vatRate(VatRate.ZERO)
                .subtotal(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .vatAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO)
                .finalAmount(BigDecimal.ZERO)
                .status(OrderStatus.COMPLETED)
                .paymentStatus(PaymentStatus.PAID)
                .paymentMethod(request.getPaymentMethod())
                .notes(request.getNotes())
                .companyPhone(companyPhone)
                .companyAddress(companyAddress)
                .customerType(customerType)
                .companyName(companyName)
                .shortName(shortName)
                .taxCode(taxCode)
                .contactName(contactName)
                .deliveryAddress(deliveryAddress)
                .createdAt(now)
                .updatedAt(now)
                .orderItems(new ArrayList<>())
                .build();

        Order savedOrder = orderRepository.save(order);

        // Build items
        Map<Long, BigDecimal> ingredientUsageMap = new LinkedHashMap<>();
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (CreateOrderRequest.OrderItemRequest itemReq : request.getItems()) {
            OrderItem item = buildOrderItem(savedOrder, itemReq, ingredientUsageMap);
            orderItems.add(item);
            subtotal = subtotal.add(item.getSubtotal());
        }

        // Trừ kho — warehouse-aware
        // Nếu user có SellerWarehouse mapping → dùng WarehouseIngredientStock
        // Nếu không (legacy) → dùng ingredient.stockQuantity
        com.nhatnam.server.entity.Warehouse warehouse =
                sellerWarehouseRepository.findBySellerId(userId)
                        .map(SellerWarehouse::getWarehouse)
                        .orElse(null);

        // Gom các dòng nguyên liệu theo ingredientId (giữ thứ tự) để phân bổ FIFO
        // sau khi trừ kho tổng hợp.
        Map<Long, List<OrderItemIngredient>> linesByIngredient = new LinkedHashMap<>();
        for (OrderItem oi : orderItems) {
            for (OrderItemIngredient oii : oi.getOrderItemIngredients()) {
                linesByIngredient
                        .computeIfAbsent(oii.getIngredientId(), k -> new ArrayList<>())
                        .add(oii);
            }
        }

        List<InventoryLog> logs = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : ingredientUsageMap.entrySet()) {
            Ingredient ing = ingredientRepository.findById(entry.getKey())
                    .orElseThrow(() -> new RuntimeException("Ingredient not found: " + entry.getKey()));
            BigDecimal needed = entry.getValue();

            BigDecimal before;
            BigDecimal after;

            if (warehouse == null) {
                // Legacy: dùng ingredient.stockQuantity
                before = ing.getStockQuantity();
                if (before.compareTo(needed) < 0)
                    throw new RuntimeException(String.format(
                            "Không đủ tồn kho '%s' (còn: %s, cần: %s %s)",
                            ing.getName(), before, needed, ing.getUnit()));
                after = before.subtract(needed);
                ing.setStockQuantity(after);
                ing.setUpdatedAt(now);
                ingredientRepository.save(ing);
            } else {
                // Kho mới: dùng WarehouseIngredientStock
                com.nhatnam.server.entity.WarehouseIngredientStock stock =
                        warehouseStockRepository
                                .findByWarehouseIdAndIngredientIdForUpdate(warehouse.getId(), ing.getId())
                                .orElseGet(() -> WarehouseIngredientStock.builder()
                                        .warehouse(warehouse).ingredient(ing)
                                        .stockQuantity(BigDecimal.ZERO).updatedAt(now).build());
                before = stock.getStockQuantity();
                if (before.compareTo(needed) < 0)
                    throw new RuntimeException(String.format(
                            "Không đủ tồn kho '%s' (còn: %s, cần: %s %s)",
                            ing.getName(), before, needed, ing.getUnit()));
                after = before.subtract(needed);
                stock.setStockQuantity(after);
                stock.setUpdatedAt(now);
                warehouseStockRepository.save(stock);
            }

            // ── FIFO: trừ theo lô cũ trước & phân bổ giá vốn về từng dòng ──────
            List<InventoryCostLotService.LotAllocation> allocs =
                    costLotService.consumeFifo(ing, warehouse, before, needed, now);
            distributeAllocationsToLines(linesByIngredient.get(ing.getId()), allocs);
            costLotService.recomputeAvgCost(ing, warehouse, now);

            BigDecimal unitCost = costLotService.weightedUnitCost(allocs);
            BigDecimal lineAmt  = costLotService.totalAmount(allocs);

            logs.add(InventoryLog.builder()
                    .ingredient(ing).order(savedOrder)
                    .action(InventoryAction.EXPORT)
                    .quantity(needed.negate())
                    .quantityBefore(before).quantityAfter(after)
                    .reason(savedOrder.getOrderCode())
                    .unitPrice(unitCost)       // giá vốn FIFO bình quân
                    .lineAmount(lineAmt)       // tổng giá vốn theo FIFO
                    .user(user).createdAt(now)
                    .warehouse(warehouse)
                    .build());
        }

        // Tính tiền
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (discountRate > 0) {
            discountAmount = subtotal
                    .multiply(BigDecimal.valueOf(discountRate))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        BigDecimal afterDiscount = subtotal.subtract(discountAmount);

        // VAT per-item
        BigDecimal totalVat = BigDecimal.ZERO;
        if (subtotal.compareTo(BigDecimal.ZERO) > 0) {
            for (OrderItem item : orderItems) {
                if (item.getVatRate() == null || item.getVatRate() == 0) continue;
                BigDecimal proportion    = item.getSubtotal().divide(subtotal, 10, RoundingMode.HALF_UP);
                BigDecimal itemAfterDisc = afterDiscount.multiply(proportion);
                BigDecimal itemVat       = itemAfterDisc
                        .multiply(BigDecimal.valueOf(item.getVatRate()))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                item.setVatAmount(itemVat);
                totalVat = totalVat.add(itemVat);
            }
        }

        BigDecimal finalAmount = afterDiscount.add(totalVat);

        savedOrder.setSubtotal(subtotal);
        savedOrder.setDiscountAmount(discountAmount);
        savedOrder.setVatAmount(totalVat);
        savedOrder.setTotalAmount(afterDiscount);
        savedOrder.setFinalAmount(finalAmount);
        savedOrder.setOrderItems(orderItems);

        if (!logs.isEmpty()) inventoryLogRepository.saveAll(logs);
        Order finalOrder = orderRepository.save(savedOrder);

        return mapToResponse(finalOrder);
    }

    // ════════════════════════════════════════════════════════════════
// RESOLVE PRICE — validate giá UI gửi lên so với giá thực tế
// ════════════════════════════════════════════════════════════════
    private ResolvedPrice resolvePrice(Product product,
                                       CreateOrderRequest.OrderItemRequest req) {
        BigDecimal base = product.getBasePrice();
        String mode = req.getPriceMode() != null
                ? req.getPriceMode().toUpperCase(Locale.ROOT) : "TIER";

        ResolvedPrice resolved = switch (mode) {
            case "BASE" -> new ResolvedPrice(base, "BASE", null, null, null);

            case "DISCOUNT_PERCENT" -> {
                int pct = req.getDiscountPercent() != null ? req.getDiscountPercent() : 0;

                // ── Giá gốc để tính giảm %:
                //   - Lẻ (RETAIL): dùng basePrice
                //   - Sỉ (WHOLESALE): dùng giá khung đầu tiên (tier thấp nhất sortOrder)
                BigDecimal baseForDiscount = base;
                if (req.getOrderType() != null
                        && req.getOrderType().equalsIgnoreCase("WHOLESALE")) {
                    List<ProductPriceTier> tiers = priceTierRepository
                            .findByProductIdSortedAsc(product.getId());
                    if (!tiers.isEmpty()) {
                        baseForDiscount = tiers.get(0).getPrice();
                    }
                }

                BigDecimal discounted = baseForDiscount
                        .multiply(BigDecimal.valueOf(100L - pct))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                yield new ResolvedPrice(discounted, "DISCOUNT_PERCENT", null, null, pct);
            }

            default -> { // TIER
                if (req.getTierId() != null) {
                    Optional<ProductPriceTier> tierOpt = priceTierRepository
                            .findByIdAndProductId(req.getTierId(), product.getId());

                    if (tierOpt.isPresent()) {
                        ProductPriceTier tier = tierOpt.get();
                        yield new ResolvedPrice(tier.getPrice(), "TIER",
                                tier.getId(), tier.getTierName(), null);
                    }

                    log.warn("[PRICE] tierId={} not found for product={} — fallback to qty={}",
                            req.getTierId(), product.getId(), req.getQuantity());
                }

                List<ProductPriceTier> matching =
                        priceTierRepository.findMatchingTiers(product.getId(), req.getQuantity());
                if (!matching.isEmpty()) {
                    ProductPriceTier best = matching.get(0);
                    yield new ResolvedPrice(best.getPrice(), "TIER",
                            best.getId(), best.getTierName(), null);
                }

                log.warn("[PRICE] No tier for product={} qty={}, using basePrice", product.getId(), req.getQuantity());
                yield new ResolvedPrice(base, "BASE", null, null, null);
            }
        };

        // ════════════════════════════════════════════════════════
        // VALIDATE: so sánh giá UI gửi lên vs giá thực tế
        // Cho phép sai lệch tối đa 1đ do làm tròn
        // ════════════════════════════════════════════════════════
        if (req.getSentUnitPrice() != null) {
            BigDecimal diff = resolved.unitPrice()
                    .subtract(req.getSentUnitPrice())
                    .abs();
            BigDecimal tolerance = new BigDecimal("1.00");

            if (diff.compareTo(tolerance) > 0) {
                String productName = product.getName();
                throw new PriceChangedException(String.format(
                        "Giá '%s' đã thay đổi (UI: %s → Thực tế: %s). " +
                                "Vui lòng xóa, refresh sản phẩm sau đó thêm lại vào giỏ hàng.",
                        productName,
                        req.getSentUnitPrice().toPlainString(),
                        resolved.unitPrice().toPlainString()
                ));
            }
        }

        return resolved;
    }

    // ════════════════════════════════════════════════════════════════
    // BUILD ORDER ITEM
    // ════════════════════════════════════════════════════════════════
    private OrderItem buildOrderItem(Order order,
                                     CreateOrderRequest.OrderItemRequest itemReq,
                                     Map<Long, BigDecimal> usageMap) {
        Product product = productRepository.findByIdAndIsActiveTrue(itemReq.getProductId())
                .orElseThrow(() -> new RuntimeException(
                        "Sản phẩm không tồn tại: " + itemReq.getProductId()));

        ProductVariant variant  = resolveVariant(product, itemReq.getVariantId());
        ResolvedPrice  resolved = resolvePrice(product, itemReq);

        BigDecimal subtotal = resolved.unitPrice()
                .multiply(itemReq.getQuantity())
                .setScale(2, RoundingMode.HALF_UP);

        int vatRatePct = product.getVatRate() != null
                ? product.getVatRate().getPercentage() : 0;

        // ✅ Lấy unit từ product, không lấy từ ingredient
        String unit = product.getUnit();
        if (unit == null || unit.isBlank()) unit = "kg";

        OrderItem item = OrderItem.builder()
                .order(order)
                .productId(product.getId())
                .productName(product.getName())
                .productImageUrl(product.getImageUrl())
                .variantId(variant != null ? variant.getId() : null)
                .variantName(variant != null ? variant.getVariantName() : null)
                .unit(unit)
                .basePrice(product.getBasePrice())
                .unitPrice(resolved.unitPrice())
                .priceMode(resolved.priceMode())
                .tierId(resolved.tierId())
                .tierName(resolved.tierName())
                .discountPercent(resolved.discountPercent())
                .quantity(itemReq.getQuantity())
                .subtotal(subtotal)
                .vatRate(vatRatePct)
                .vatAmount(BigDecimal.ZERO)
                .notes(itemReq.getNotes())
                .orderItemIngredients(new ArrayList<>())
                .build();

        item.setOrderItemIngredients(
                collectIngredients(item, product, variant, itemReq.getQuantity(), usageMap));
        return item;
    }

    private ProductVariant resolveVariant(Product product, Long variantId) {
        if (variantId != null)
            return variantRepository.findById(variantId)
                    .orElseThrow(() -> new RuntimeException("Variant not found: " + variantId));
        List<ProductVariant> list =
                variantRepository.findByProductIdAndIsActiveTrue(product.getId());
        return list.stream().filter(ProductVariant::getIsDefault).findFirst()
                .orElse(list.isEmpty() ? null : list.get(0));
    }

    private List<OrderItemIngredient> collectIngredients(
            OrderItem orderItem, Product product, ProductVariant variant,
            BigDecimal quantity, Map<Long, BigDecimal> usageMap) {

        List<OrderItemIngredient> result = new ArrayList<>();

        List<VariantIngredient> vis = variant != null
                ? variantIngredientRepository.findByVariantId(variant.getId())
                : Collections.emptyList();

        if (!vis.isEmpty()) {
            for (VariantIngredient vi : vis) {
                Ingredient ing = vi.getIngredient();
                BigDecimal usedQty = quantity;

                // ── Lấy giá vốn hiện tại của nguyên liệu ──
                BigDecimal costPrice = ing.getCostPrice() != null
                        ? ing.getCostPrice()
                        : BigDecimal.ZERO;
                BigDecimal costAmount = costPrice.multiply(usedQty)
                        .setScale(2, RoundingMode.HALF_UP);

                usageMap.merge(ing.getId(), usedQty, BigDecimal::add);
                result.add(OrderItemIngredient.builder()
                        .orderItem(orderItem)
                        .ingredientId(ing.getId())
                        .ingredientName(ing.getName())
                        .ingredientImageUrl(ing.getImageUrl())
                        .quantityUsed(usedQty)
                        .unit(ing.getUnit())
                        .costPrice(costPrice)      // ← THÊM
                        .costAmount(costAmount)    // ← THÊM
                        .build());
            }
        } else {
            // Direct product ingredients
            for (ProductIngredient pi : product.getProductIngredients()) {
                Ingredient ing = pi.getIngredient();
                BigDecimal ingQtyPerUnit = pi.getQuantity() != null
                        && pi.getQuantity().compareTo(BigDecimal.ZERO) > 0
                        ? pi.getQuantity() : BigDecimal.ONE;
                BigDecimal usedQty = ingQtyPerUnit.multiply(quantity)
                        .setScale(3, RoundingMode.HALF_UP);

                // ── Lấy giá vốn hiện tại của nguyên liệu ──
                BigDecimal costPrice = ing.getCostPrice() != null
                        ? ing.getCostPrice()
                        : BigDecimal.ZERO;
                BigDecimal costAmount = costPrice.multiply(usedQty)
                        .setScale(2, RoundingMode.HALF_UP);

                usageMap.merge(ing.getId(), usedQty, BigDecimal::add);
                result.add(OrderItemIngredient.builder()
                        .orderItem(orderItem)
                        .ingredientId(ing.getId())
                        .ingredientName(ing.getName())
                        .ingredientImageUrl(ing.getImageUrl())
                        .quantityUsed(usedQty)
                        .unit(ing.getUnit())
                        .costPrice(costPrice)      // ← THÊM
                        .costAmount(costAmount)    // ← THÊM
                        .build());
            }
        }
        return result;
    }


    // ════════════════════════════════════════════════════════════════
    // MAP TO RESPONSE
    // ════════════════════════════════════════════════════════════════
    private OrderResponse mapToResponse(Order order) {

        List<OrderResponse.OrderItemResponse> itemResponses = order.getOrderItems().stream()
                .map(item -> {
                    String priceLabel = buildPriceLabel(item);
                    return OrderResponse.OrderItemResponse.builder()
                            .id(item.getId())
                            .productId(item.getProductId())
                            .productName(item.getProductName())
                            .productImageUrl(item.getProductImageUrl())
                            .variantId(item.getVariantId())
                            .variantName(item.getVariantName())
                            .unit(item.getUnit())
                            .basePrice(item.getBasePrice())
                            .unitPrice(item.getUnitPrice())
                            .priceMode(item.getPriceMode())
                            .priceName(priceLabel)
                            .defaultPrice(item.getBasePrice())
                            .tierId(item.getTierId())
                            .tierName(item.getTierName())
                            .discountPercent(item.getDiscountPercent())
                            .vatRate(item.getVatRate())
                            .vatAmount(item.getVatAmount())
                            .quantity(item.getQuantity())
                            .subtotal(item.getSubtotal())
                            .notes(item.getNotes())
                            .ingredientsUsed(item.getOrderItemIngredients().stream()
                                    .map(ii -> OrderResponse.IngredientUsed.builder()
                                            .ingredientId(ii.getIngredientId())
                                            .ingredientName(ii.getIngredientName())
                                            .ingredientImageUrl(ii.getIngredientImageUrl())
                                            .quantityUsed(ii.getQuantityUsed())
                                            .unit(ii.getUnit())
                                            .build())
                                    .collect(Collectors.toList()))
                            .build();
                })
                .collect(Collectors.toList());

        return OrderResponse.builder()
                .id(order.getId())
                .orderCode(order.getOrderCode())
                .customerName(order.getCustomerName())
                .customerPhone(order.getCustomerPhone())
                .customerEmail(order.getCustomerEmail())
                .shippingAddress(order.getShippingAddress())
                .subtotal(order.getSubtotal())
                .discountAmount(order.getDiscountAmount())
                .vatAmount(order.getVatAmount())
                .totalAmount(order.getSubtotal())
                .finalAmount(order.getFinalAmount())
                .status(order.getStatus().name())
                .paymentStatus(order.getPaymentStatus().name())
                .paymentMethod(order.getPaymentMethod())
                .notes(order.getNotes())
                .customerType(order.getCustomerType())
                .companyName(order.getCompanyName())
                .shortName(order.getShortName())
                .taxCode(order.getTaxCode())
                .contactName(order.getContactName())
                .deliveryAddress(order.getDeliveryAddress())
                .companyPhone(order.getCompanyPhone())
                .companyAddress(order.getCompanyAddress())
                .createdAt(order.getCreatedAt())
                .items(itemResponses)
                .build();
    }

    // ════════════════════════════════════════════════════════════════
    // PUBLIC interface methods
    // ════════════════════════════════════════════════════════════════

    @Override
    public OrderResponse getOrderById(Long id) {
        return mapToResponse(orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found")));
    }

    @Override
    public OrderResponse getOrderByCode(String orderCode) {
        return mapToResponse(orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new RuntimeException("Order not found")));
    }

    @Override
    public List<OrderResponse> getMyOrders(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    public List<OrderResponse> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        order.setStatus(newStatus);
        order.setUpdatedAt(System.currentTimeMillis());
        return mapToResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        if (order.getStatus() != OrderStatus.PENDING)
            throw new RuntimeException("Can only cancel PENDING orders");
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(System.currentTimeMillis());
        return mapToResponse(orderRepository.save(order));
    }

    // ════════════════════════════════════════════════════════════════
    // FIFO: phân bổ allocation (theo lô) về từng dòng nguyên liệu của đơn
    // ════════════════════════════════════════════════════════════════
    private void distributeAllocationsToLines(
            List<OrderItemIngredient> lines,
            List<InventoryCostLotService.LotAllocation> allocs) {

        if (lines == null || lines.isEmpty() || allocs == null || allocs.isEmpty()) return;

        int ai = 0;
        BigDecimal allocRemaining = allocs.get(0).quantity();

        for (OrderItemIngredient line : lines) {
            BigDecimal need = line.getQuantityUsed() != null
                    ? line.getQuantityUsed() : BigDecimal.ZERO;
            List<OrderItemIngredientLot> lotRows = new ArrayList<>();
            BigDecimal costAmt = BigDecimal.ZERO;

            while (need.signum() > 0 && ai < allocs.size()) {
                InventoryCostLotService.LotAllocation a = allocs.get(ai);
                BigDecimal take = allocRemaining.min(need);
                if (take.signum() > 0) {
                    lotRows.add(OrderItemIngredientLot.builder()
                            .orderItemIngredient(line)
                            .costLotId(a.lotId())
                            .sourceRef(a.sourceRef())
                            .unitCost(a.unitCost())
                            .quantity(take)
                            .build());
                    costAmt = costAmt.add(a.unitCost().multiply(take));
                    need = need.subtract(take);
                    allocRemaining = allocRemaining.subtract(take);
                }
                if (allocRemaining.signum() <= 0) {
                    ai++;
                    if (ai < allocs.size()) allocRemaining = allocs.get(ai).quantity();
                }
            }

            line.setLots(lotRows);
            BigDecimal costAmount = costAmt.setScale(2, RoundingMode.HALF_UP);
            line.setCostAmount(costAmount);
            BigDecimal q = line.getQuantityUsed();
            line.setCostPrice((q != null && q.signum() > 0)
                    ? costAmount.divide(q, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // SUPER ADMIN: HỦY ĐƠN HÀNG (lịch sử đơn sỉ/lẻ)
    //   1) Cộng lại tồn kho
    //   2) Cộng lại số lượng vào ĐÚNG lô giá vốn (FIFO)
    // ════════════════════════════════════════════════════════════════
    @Override
    @Transactional
    public OrderResponse cancelSaleOrder(Long orderId) {
        long now = System.currentTimeMillis();

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));

        if (order.getStatus() == OrderStatus.CANCELLED)
            throw new RuntimeException("Đơn hàng đã được hủy trước đó.");

        // Kho nơi đơn đã trừ (theo seller tạo đơn) — giống lúc tạo đơn
        Long sellerId = order.getUser() != null ? order.getUser().getId() : null;
        com.nhatnam.server.entity.Warehouse warehouse = sellerId == null ? null :
                sellerWarehouseRepository.findBySellerId(sellerId)
                        .map(SellerWarehouse::getWarehouse)
                        .orElse(null);

        // Gom số lượng cần cộng trả theo từng nguyên liệu (để cộng tồn + ghi log)
        Map<Long, BigDecimal> restoreQtyByIng = new LinkedHashMap<>();

        for (OrderItem item : order.getOrderItems()) {
            for (OrderItemIngredient oii : item.getOrderItemIngredients()) {
                Long ingId = oii.getIngredientId();
                BigDecimal used = oii.getQuantityUsed() != null
                        ? oii.getQuantityUsed() : BigDecimal.ZERO;
                if (ingId == null || used.signum() <= 0) continue;

                restoreQtyByIng.merge(ingId, used, BigDecimal::add);

                Ingredient ing = ingredientRepository.findById(ingId).orElse(null);
                if (ing == null) continue;

                List<OrderItemIngredientLot> lots = oii.getLots();
                if (lots != null && !lots.isEmpty()) {
                    // Cộng trả về ĐÚNG từng lô giá vốn (ví dụ 100@1.000 + 20@1.200)
                    for (OrderItemIngredientLot lot : lots) {
                        costLotService.restoreToLot(
                                lot.getCostLotId(), ing, warehouse,
                                lot.getUnitCost(), lot.getQuantity(),
                                "CANCEL-" + order.getOrderCode(), now);
                    }
                } else {
                    // Đơn cũ (trước khi có dữ liệu lô) → tạo lại 1 lô theo giá vốn snapshot
                    costLotService.restoreToLot(
                            null, ing, warehouse,
                            oii.getCostPrice(), used,
                            "CANCEL-" + order.getOrderCode(), now);
                }
            }
        }

        // Cộng lại tồn kho + ghi log điều chỉnh + tính lại giá vốn bình quân
        List<InventoryLog> logs = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : restoreQtyByIng.entrySet()) {
            Ingredient ing = ingredientRepository.findById(e.getKey()).orElse(null);
            if (ing == null) continue;
            BigDecimal qty = e.getValue();

            BigDecimal before = getStockForCancel(ing, warehouse);
            BigDecimal after  = before.add(qty);
            addStockForCancel(ing, warehouse, after, now);

            costLotService.recomputeAvgCost(ing, warehouse, now);

            logs.add(InventoryLog.builder()
                    .ingredient(ing).order(order)
                    .action(InventoryAction.ADJUST)
                    .quantity(qty)               // dương = cộng trả
                    .quantityBefore(before).quantityAfter(after)
                    .reason("HỦY ĐƠN " + order.getOrderCode())
                    .user(order.getUser())
                    .warehouse(warehouse)
                    .createdAt(now)
                    .build());
        }
        if (!logs.isEmpty()) inventoryLogRepository.saveAll(logs);

        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(now);
        Order saved = orderRepository.save(order);
        return mapToResponse(saved);
    }

    private BigDecimal getStockForCancel(
            Ingredient ing, com.nhatnam.server.entity.Warehouse warehouse) {
        if (warehouse == null) return ing.getStockQuantity();
        return warehouseStockRepository
                .findByWarehouseIdAndIngredientId(warehouse.getId(), ing.getId())
                .map(WarehouseIngredientStock::getStockQuantity)
                .orElse(BigDecimal.ZERO);
    }

    private void addStockForCancel(
            Ingredient ing, com.nhatnam.server.entity.Warehouse warehouse,
            BigDecimal newQty, long now) {
        if (warehouse == null) {
            ing.setStockQuantity(newQty);
            ing.setUpdatedAt(now);
            ingredientRepository.save(ing);
        } else {
            WarehouseIngredientStock stock = warehouseStockRepository
                    .findByWarehouseIdAndIngredientId(warehouse.getId(), ing.getId())
                    .orElseGet(() -> WarehouseIngredientStock.builder()
                            .warehouse(warehouse).ingredient(ing)
                            .stockQuantity(BigDecimal.ZERO).updatedAt(now).build());
            stock.setStockQuantity(newQty);
            stock.setUpdatedAt(now);
            warehouseStockRepository.save(stock);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────
    private String generateOrderCode() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();

        long start = today.atStartOfDay(zone).toEpochSecond() * 1000;
        long end   = today.plusDays(1).atStartOfDay(zone).toEpochSecond() * 1000;

        long count = orderRepository.countByCreatedAtBetween(start, end) + 1;

        return String.format("ORD-%s-%010d", date, count);
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}