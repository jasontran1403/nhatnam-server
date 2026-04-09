package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.DashboardDto;
import com.nhatnam.server.dto.PosDashboardDto;
import com.nhatnam.server.enumtype.OrderSource;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PosOrderStatus;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.utils.RegionExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
@Transactional(readOnly = true)
public class DashboardService {

    private final RegionExtractor regionExtractor;

    @PersistenceContext
    private EntityManager em;

    private final OrderRepository orderRepository;

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(
            OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PREPARING,
            OrderStatus.READY, OrderStatus.DELIVERING
    );

    // ══════════════════════════════════════════════════════════
    // RESTAURANT DASHBOARD
    // ══════════════════════════════════════════════════════════

    public DashboardDto.RestaurantDashboard getRestaurantDashboard(
            DashboardDto.DateRangeFilter filter, String granularity, String mode) {
        Long from = filter.getFromTs();
        Long to   = filter.getToTs();

        // mode: "wholesale" → type=WHOLESALE, "retail" → type=RETAIL, null → tất cả
        String orderType = mode == null ? null
                : mode.equalsIgnoreCase("wholesale") ? "WHOLESALE" : "RETAIL";

        return DashboardDto.RestaurantDashboard.builder()
                .orderSummary(getOrderSummary(from, to, orderType))
                .revenueSummary(getRevenueSummary(from, to, orderType))
                .customerSummary(getCustomerSummary(from, to, orderType))
                .paymentBreakdown(getPaymentBreakdown(from, to, orderType))
                .topProducts(getTopProducts(from, to, 10, orderType))
                .topIngredients(getTopIngredients(from, to, 10, orderType))
                .topCustomers(getTopCustomers(from, to, 10, orderType))
                .topUsers(getTopUsers(from, to, 10, orderType))
                .ordersByTime(getOrdersByTime(from, to, granularity, orderType))
                .regionBreakdown(getRegionBreakdown(from, to, orderType))
                .recentOrders(getRecentOrders(from, to, 5, orderType))
                .build();
    }

    // ══════════════════════════════════════════════════════════
    // POS DASHBOARD
    // ══════════════════════════════════════════════════════════

    public List<PosDashboardDto.PosOrderByTime> getPosOrdersByTimePublic(
            PosDashboardDto.DateRangeFilter filter,
            String granularity, Long storeId, String filterType) {
        return getPosOrdersByTime(
                filter.getFromTs(), filter.getToTs(), granularity, storeId, filterType);
    }

    public PosDashboardDto.PosDashboard getPosDashboard(
            PosDashboardDto.DateRangeFilter filter, String granularity, Long storeId) {
        return getPosDashboard(filter, granularity, storeId, null);
    }

    public PosDashboardDto.PosDashboard getPosDashboard(
            PosDashboardDto.DateRangeFilter filter,
            String granularity, Long storeId, String filterType) {
        Long from = filter.getFromTs();
        Long to   = filter.getToTs();
        return PosDashboardDto.PosDashboard.builder()
                .orderSummary(getPosOrderSummary(from, to, storeId))
                .revenueSummary(getPosRevenueSummary(from, to, storeId))
                .paymentBreakdown(getPosPaymentBreakdown(from, to, storeId))
                .topProducts(getPosTopProducts(from, to, 10, storeId))
                .ordersByTime(getPosOrdersByTime(from, to, granularity, storeId, filterType))
                .recentOrders(getPosRecentOrders(from, to, 10, storeId))
                .sourceStats(getPosSourceStats(from, to, storeId))
                .build();
    }

    // ══════════════════════════════════════════════════════════
    // RESTAURANT — ORDER SUMMARY
    // ══════════════════════════════════════════════════════════

    private DashboardDto.OrderSummary getOrderSummary(Long from, Long to, String orderType) {
        List<Object[]> rows = em.createQuery(
                        "SELECT o.status, COUNT(o) FROM Order o " +
                                "WHERE (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType) " + // ← THÊM
                                "GROUP BY o.status", Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("orderType", orderType) // ← THÊM
                .getResultList();

        Map<String, Long> map = new HashMap<>();
        long total = 0;
        for (Object[] r : rows) {
            String status = ((Enum<?>) r[0]).name();
            long   cnt    = (Long) r[1];
            map.put(status, cnt);
            total += cnt;
        }
        return DashboardDto.OrderSummary.builder()
                .totalOrders(total)
                .pendingOrders(map.getOrDefault("PENDING", 0L))
                .confirmedOrders(map.getOrDefault("CONFIRMED", 0L))
                .preparingOrders(map.getOrDefault("PREPARING", 0L))
                .readyOrders(map.getOrDefault("READY", 0L))
                .deliveringOrders(map.getOrDefault("DELIVERING", 0L))
                .completedOrders(map.getOrDefault("COMPLETED", 0L))
                .cancelledOrders(map.getOrDefault("CANCELLED", 0L))
                .failedOrders(map.getOrDefault("FAILED", 0L))
                .build();
    }

    private static String paymentLabel(String method) {
        return switch (method) {
            case "CASH" -> "Tiền mặt"; case "BANK_TRANSFER" -> "Chuyển khoản";
            case "MOMO" -> "MoMo"; case "VNPAY" -> "VNPay"; case "ZALOPAY" -> "ZaloPay";
            default -> method;
        };
    }

    private DashboardDto.PaymentBreakdown getPaymentBreakdown(Long from, Long to) {
        List<Object[]> rows = em.createQuery(
                        "SELECT o.paymentMethod, COUNT(o), COALESCE(SUM(o.finalAmount),0) " +
                                "FROM Order o WHERE o.status = :status " +
                                "  AND (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "GROUP BY o.paymentMethod ORDER BY SUM(o.finalAmount) DESC", Object[].class)
                .setParameter("status", OrderStatus.COMPLETED)
                .setParameter("from", from).setParameter("to", to).getResultList();

        List<DashboardDto.PaymentMethodItem> methods = new ArrayList<>();
        for (Object[] r : rows) {
            methods.add(DashboardDto.PaymentMethodItem.builder()
                    .method(r[0] != null ? r[0].toString() : "OTHER")
                    .label(paymentLabel(r[0] != null ? r[0].toString() : "OTHER"))
                    .amount((BigDecimal) r[2]).count((Long) r[1]).build());
        }
        return DashboardDto.PaymentBreakdown.builder().methods(methods).build();
    }

    // ══════════════════════════════════════════════════════════
    // RESTAURANT — TOP PRODUCTS / INGREDIENTS / CUSTOMERS / USERS
    // ══════════════════════════════════════════════════════════

    private DashboardDto.RevenueSummary getRevenueSummary(Long from, Long to, String orderType) {
        Object[] completedRow = (Object[]) em.createQuery(
                        "SELECT COALESCE(SUM(o.finalAmount),0), COALESCE(SUM(o.discountAmount),0), " +
                                "       COALESCE(SUM(o.vatAmount),0) FROM Order o " +
                                "WHERE o.status = :status " +
                                "  AND (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType)")
                .setParameter("status", OrderStatus.COMPLETED)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType)
                .getSingleResult();

        BigDecimal pendingRevenue = (BigDecimal) em.createQuery(
                        "SELECT COALESCE(SUM(o.finalAmount),0) FROM Order o " +
                                "WHERE o.status IN :statuses " +
                                "  AND (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType)")
                .setParameter("statuses", ACTIVE_STATUSES)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType)
                .getSingleResult();

        return DashboardDto.RevenueSummary.builder()
                .completedRevenue((BigDecimal) completedRow[0])
                .totalDiscount((BigDecimal) completedRow[1])
                .totalVat((BigDecimal) completedRow[2])
                .pendingRevenue(pendingRevenue != null ? pendingRevenue : BigDecimal.ZERO)
                .build();
    }

    private DashboardDto.CustomerSummary getCustomerSummary(Long from, Long to, String orderType) {
        Long newCustomers = (Long) em.createQuery(
                        "SELECT COUNT(DISTINCT o.customer.id) FROM Order o " +
                                "WHERE o.customer IS NOT NULL " +
                                "  AND (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType) " +
                                "  AND o.customer.id NOT IN (" +
                                "      SELECT DISTINCT o2.customer.id FROM Order o2 " +
                                "      WHERE o2.customer IS NOT NULL " +
                                "        AND :from IS NOT NULL AND o2.createdAt < :from " +
                                "        AND (:orderType IS NULL OR o2.type = :orderType))")
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType).getSingleResult();

        Long returningCustomers = (Long) em.createQuery(
                        "SELECT COUNT(DISTINCT o.customer.id) FROM Order o " +
                                "WHERE o.customer IS NOT NULL " +
                                "  AND (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType) " +
                                "  AND o.customer.id IN (" +
                                "      SELECT DISTINCT o2.customer.id FROM Order o2 " +
                                "      WHERE o2.customer IS NOT NULL " +
                                "        AND :from IS NOT NULL AND o2.createdAt < :from " +
                                "        AND (:orderType IS NULL OR o2.type = :orderType))")
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType).getSingleResult();

        return DashboardDto.CustomerSummary.builder()
                .newCustomers(newCustomers == null ? 0L : newCustomers)
                .returningCustomers(returningCustomers == null ? 0L : returningCustomers)
                .build();
    }

    private DashboardDto.PaymentBreakdown getPaymentBreakdown(Long from, Long to, String orderType) {
        List<Object[]> rows = em.createQuery(
                        "SELECT o.paymentMethod, COUNT(o), COALESCE(SUM(o.finalAmount),0) " +
                                "FROM Order o WHERE o.status = :status " +
                                "  AND (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType) " +
                                "GROUP BY o.paymentMethod ORDER BY SUM(o.finalAmount) DESC", Object[].class)
                .setParameter("status", OrderStatus.COMPLETED)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType).getResultList();

        List<DashboardDto.PaymentMethodItem> methods = new ArrayList<>();
        for (Object[] r : rows) {
            methods.add(DashboardDto.PaymentMethodItem.builder()
                    .method(r[0] != null ? r[0].toString() : "OTHER")
                    .label(paymentLabel(r[0] != null ? r[0].toString() : "OTHER"))
                    .amount((BigDecimal) r[2]).count((Long) r[1]).build());
        }
        return DashboardDto.PaymentBreakdown.builder().methods(methods).build();
    }

    private List<DashboardDto.TopProduct> getTopProducts(Long from, Long to, int limit, String orderType) {
        return em.createQuery(
                        "SELECT oi.productId, MAX(oi.productName), MAX(oi.productImageUrl), " +
                                "       SUM(oi.quantity), SUM(oi.subtotal), COUNT(DISTINCT oi.order.id) " +
                                "FROM OrderItem oi " +
                                "WHERE (:from IS NULL OR oi.order.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR oi.order.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR oi.order.type = :orderType) " +
                                "GROUP BY oi.productId ORDER BY SUM(oi.subtotal) DESC", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType).setMaxResults(limit)
                .getResultList().stream().map(r -> DashboardDto.TopProduct.builder()
                        .productId((Long) r[0]).productName((String) r[1])
                        .productImageUrl((String) r[2]).totalQuantity((BigDecimal) r[3])
                        .totalRevenue((BigDecimal) r[4]).orderCount((Long) r[5]).build())
                .collect(Collectors.toList());
    }

    private List<DashboardDto.TopIngredient> getTopIngredients(Long from, Long to, int limit, String orderType) {
        return em.createQuery(
                        "SELECT oii.ingredientId, MAX(oii.ingredientName), MAX(oii.unit), " +
                                "       SUM(oii.quantityUsed), COUNT(oii.id) " +
                                "FROM OrderItemIngredient oii " +
                                "WHERE (:from IS NULL OR oii.orderItem.order.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR oii.orderItem.order.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR oii.orderItem.order.type = :orderType) " +
                                "GROUP BY oii.ingredientId ORDER BY SUM(oii.quantityUsed) DESC", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType).setMaxResults(limit)
                .getResultList().stream().map(r -> DashboardDto.TopIngredient.builder()
                        .ingredientId((Long) r[0]).ingredientName((String) r[1])
                        .unit((String) r[2]).totalQuantityUsed((BigDecimal) r[3])
                        .usageCount((Long) r[4]).build())
                .collect(Collectors.toList());
    }

    private List<DashboardDto.TopCustomer> getTopCustomers(Long from, Long to, int limit, String orderType) {
        return em.createQuery(
                        "SELECT o.customer.id, MAX(o.customerName), MAX(o.customerPhone), " +
                                "       COUNT(o.id), COALESCE(SUM(o.finalAmount),0) " +
                                "FROM Order o WHERE o.customer IS NOT NULL " +
                                "  AND (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType) " +
                                "GROUP BY o.customer.id ORDER BY SUM(o.finalAmount) DESC", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType).setMaxResults(limit)
                .getResultList().stream().map(r -> DashboardDto.TopCustomer.builder()
                        .customerId((Long) r[0]).customerName((String) r[1])
                        .customerPhone((String) r[2]).orderCount((Long) r[3])
                        .totalSpent((BigDecimal) r[4]).build())
                .collect(Collectors.toList());
    }

    private List<DashboardDto.TopUser> getTopUsers(Long from, Long to, int limit, String orderType) {
        return em.createQuery(
                        "SELECT o.user.id, MAX(o.user.username), MAX(o.user.fullName), " +
                                "       COUNT(o.id), COALESCE(SUM(o.finalAmount),0) " +
                                "FROM Order o " +
                                "WHERE (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType) " +
                                "GROUP BY o.user.id ORDER BY COUNT(o.id) DESC", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType).setMaxResults(limit)
                .getResultList().stream().map(r -> DashboardDto.TopUser.builder()
                        .userId((Long) r[0]).userName((String) r[1]).fullName((String) r[2])
                        .orderCount((Long) r[3]).totalRevenue((BigDecimal) r[4]).build())
                .collect(Collectors.toList());
    }

    private List<DashboardDto.OrderByTime> getOrdersByTime(Long from, Long to, String granularity, String orderType) {
        List<Object[]> rows = em.createQuery(
                        "SELECT o.createdAt, o.finalAmount FROM Order o " +
                                "WHERE (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType) " +
                                "ORDER BY o.createdAt ASC", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType).getResultList();

        Map<String, long[]>     buckets = new LinkedHashMap<>();
        Map<String, BigDecimal> revMap  = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String bucket = toBucket((Long) r[0], granularity);
            buckets.computeIfAbsent(bucket, k -> new long[]{0})[0]++;
            revMap.merge(bucket, r[1] != null ? (BigDecimal) r[1] : BigDecimal.ZERO, BigDecimal::add);
        }
        return buckets.entrySet().stream().map(e -> DashboardDto.OrderByTime.builder()
                        .timeBucket(e.getKey()).orderCount(e.getValue()[0])
                        .revenue(revMap.getOrDefault(e.getKey(), BigDecimal.ZERO)).build())
                .collect(Collectors.toList());
    }

    private List<DashboardDto.RegionBreakdown> getRegionBreakdown(Long from, Long to, String orderType) {
        List<Object[]> rows = em.createQuery(
                        "SELECT o.shippingAddress, COUNT(o.id), COALESCE(SUM(o.finalAmount),0) " +
                                "FROM Order o WHERE o.shippingAddress IS NOT NULL " +
                                "  AND (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType) " +
                                "GROUP BY o.shippingAddress", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType).getResultList();

        Map<String, long[]>     countMap = new LinkedHashMap<>();
        Map<String, BigDecimal> revMap   = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String region = regionExtractor.extractRegion((String) r[0]);
            countMap.computeIfAbsent(region, k -> new long[]{0})[0] += (Long) r[1];
            revMap.merge(region, (BigDecimal) r[2], BigDecimal::add);
        }
        return countMap.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .map(e -> DashboardDto.RegionBreakdown.builder()
                        .region(e.getKey()).orderCount(e.getValue()[0])
                        .revenue(revMap.getOrDefault(e.getKey(), BigDecimal.ZERO)).build())
                .collect(Collectors.toList());
    }

    private List<DashboardDto.RecentOrder> getRecentOrders(Long from, Long to, int limit, String orderType) {
        return em.createQuery(
                        "SELECT o.id, o.orderCode, o.customerName, o.createdAt, " +
                                "       o.totalAmount, o.discountAmount, o.vatAmount, o.finalAmount, " +
                                "       o.status, o.paymentStatus FROM Order o " +
                                "WHERE (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND (:orderType IS NULL OR o.type = :orderType) " +
                                "ORDER BY o.createdAt DESC", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("orderType", orderType).setMaxResults(limit)
                .getResultList().stream().map(r -> DashboardDto.RecentOrder.builder()
                        .orderId((Long) r[0]).orderCode((String) r[1])
                        .customerName((String) r[2]).createdAt((Long) r[3])
                        .totalAmount(r[4] != null ? (BigDecimal) r[4] : BigDecimal.ZERO)
                        .discountAmount(r[5] != null ? (BigDecimal) r[5] : BigDecimal.ZERO)
                        .vatAmount(r[6] != null ? (BigDecimal) r[6] : BigDecimal.ZERO)
                        .finalAmount(r[7] != null ? (BigDecimal) r[7] : BigDecimal.ZERO)
                        .status(r[8] != null ? ((Enum<?>) r[8]).name() : null)
                        .paymentStatus(r[9] != null ? ((Enum<?>) r[9]).name() : null).build())
                .collect(Collectors.toList());
    }


    // ══════════════════════════════════════════════════════════
    // POS — ORDER SUMMARY
    // ══════════════════════════════════════════════════════════

    private PosDashboardDto.PosOrderSummary getPosOrderSummary(Long from, Long to, Long storeId) {
        List<Object[]> rows = em.createQuery(
                        "SELECT po.orderSource, po.status, COUNT(po) FROM PosOrder po " +
                                "WHERE (:from IS NULL OR po.createdAt >= :from) " +
                                "  AND (:to IS NULL OR po.createdAt <= :to) " +
                                "  AND (:storeId IS NULL OR po.store.id = :storeId) " +
                                "GROUP BY po.orderSource, po.status", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("storeId", storeId).getResultList();

        long offline = 0, shopee = 0, grab = 0, total = 0;
        long completed = 0, cancelled = 0, pending = 0;
        long offlineCompleted = 0, offlinePending = 0;
        long shopeeCompleted = 0, shopeePending = 0;
        long grabCompleted = 0, grabPending = 0;

        for (Object[] r : rows) {
            OrderSource    source = (OrderSource) r[0];
            PosOrderStatus status = (PosOrderStatus) r[1];
            long           cnt    = (Long) r[2];
            total += cnt;
            if (source == OrderSource.TAKE_AWAY || source == OrderSource.DINE_IN) offline += cnt;
            else if (source == OrderSource.SHOPEE_FOOD) shopee += cnt;
            else if (source == OrderSource.GRAB_FOOD)   grab   += cnt;
            if (status == PosOrderStatus.COMPLETED)      completed += cnt;
            else if (status == PosOrderStatus.CANCELLED) cancelled += cnt;
            else if (status == PosOrderStatus.PENDING)   pending   += cnt;
            if (status == PosOrderStatus.COMPLETED) {
                if (source == OrderSource.TAKE_AWAY || source == OrderSource.DINE_IN)
                    offlineCompleted += cnt;
                else if (source == OrderSource.SHOPEE_FOOD) shopeeCompleted += cnt;
                else if (source == OrderSource.GRAB_FOOD)   grabCompleted   += cnt;
            } else if (status == PosOrderStatus.PENDING) {
                if (source == OrderSource.TAKE_AWAY || source == OrderSource.DINE_IN)
                    offlinePending += cnt;
                else if (source == OrderSource.SHOPEE_FOOD) shopeePending += cnt;
                else if (source == OrderSource.GRAB_FOOD)   grabPending   += cnt;
            }
        }
        return PosDashboardDto.PosOrderSummary.builder()
                .offlineOrders(offline).shopeeFoodOrders(shopee).grabFoodOrders(grab)
                .totalOrders(total).completedOrders(completed)
                .cancelledOrders(cancelled).pendingOrders(pending)
                .offlineOrdersCompleted(offlineCompleted).offlineOrdersPending(offlinePending)
                .shopeeFoodOrdersCompleted(shopeeCompleted).shopeeFoodOrdersPending(shopeePending)
                .grabFoodOrdersCompleted(grabCompleted).grabFoodOrdersPending(grabPending)
                .build();
    }

    // ══════════════════════════════════════════════════════════
    // POS — REVENUE SUMMARY
    // ══════════════════════════════════════════════════════════

    private PosDashboardDto.PosRevenueSummary getPosRevenueSummary(Long from, Long to, Long storeId) {
        List<Object[]> rows = em.createQuery(
                        "SELECT po.orderSource, COALESCE(SUM(po.finalAmount), 0) FROM PosOrder po " +
                                "WHERE po.status = :status " +
                                "  AND (:from IS NULL OR po.createdAt >= :from) " +
                                "  AND (:to IS NULL OR po.createdAt <= :to) " +
                                "  AND (:storeId IS NULL OR po.store.id = :storeId) " +
                                "GROUP BY po.orderSource", Object[].class)
                .setParameter("status", PosOrderStatus.COMPLETED)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("storeId", storeId).getResultList();

        BigDecimal offlineRev = BigDecimal.ZERO, shopeeRev = BigDecimal.ZERO, grabRev = BigDecimal.ZERO;
        for (Object[] r : rows) {
            OrderSource src = (OrderSource) r[0];
            BigDecimal  amt = (BigDecimal) r[1];
            if (src == OrderSource.TAKE_AWAY || src == OrderSource.DINE_IN) offlineRev = amt;
            else if (src == OrderSource.SHOPEE_FOOD) shopeeRev = amt;
            else if (src == OrderSource.GRAB_FOOD)   grabRev   = amt;
        }
        return PosDashboardDto.PosRevenueSummary.builder()
                .totalRevenue(offlineRev.add(shopeeRev).add(grabRev))
                .offlineRevenue(offlineRev).shopeeFoodRevenue(shopeeRev).grabFoodRevenue(grabRev)
                .build();
    }

    // ══════════════════════════════════════════════════════════
    // POS — SOURCE STATS
    // ═══════════════════════════════════════════════════════════

    private List<PosDashboardDto.PosSourceStat> getPosSourceStats(Long from, Long to, Long storeId) {
        List<Object[]> orderRows = em.createQuery(
                        "SELECT po.orderSource, COUNT(po), COALESCE(SUM(po.finalAmount), 0) " +
                                "FROM PosOrder po " +
                                "WHERE (:from IS NULL OR po.createdAt >= :from) " +
                                "  AND (:to IS NULL OR po.createdAt <= :to) " +
                                "  AND (:storeId IS NULL OR po.store.id = :storeId) " +
                                "GROUP BY po.orderSource", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("storeId", storeId).getResultList();

        // ── FIX: COUNT(poi.id) thay vì SUM(poi.quantity) ──────────────────
        // Đếm số dòng order_item (số món), không nhân quantity
        List<Object[]> itemRows = em.createQuery(
                        "SELECT po.orderSource, COUNT(poi.id) " +
                                "FROM PosOrderItem poi JOIN poi.order po " +
                                "WHERE (:from IS NULL OR po.createdAt >= :from) " +
                                "  AND (:to IS NULL OR po.createdAt <= :to) " +
                                "  AND (:storeId IS NULL OR po.store.id = :storeId) " +
                                "GROUP BY po.orderSource", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("storeId", storeId).getResultList();

        // COUNT(poi.id) luôn trả về Long — không cần instanceof BigDecimal
        Map<String, Long> itemMap = new HashMap<>();
        for (Object[] r : itemRows) {
            String key = r[0] != null ? ((Enum<?>) r[0]).name() : "TAKE_AWAY";
            itemMap.put(key, (Long) r[1]);
        }

        List<String> allSources = List.of("TAKE_AWAY", "DINE_IN", "SHOPEE_FOOD", "GRAB_FOOD");
        Map<String, PosDashboardDto.PosSourceStat> statMap = new LinkedHashMap<>();
        for (String src : allSources) {
            statMap.put(src, PosDashboardDto.PosSourceStat.builder()
                    .source(src).totalItems(0L).totalOrders(0L)
                    .totalRevenue(BigDecimal.ZERO).build());
        }
        for (Object[] r : orderRows) {
            String     src     = r[0] != null ? ((Enum<?>) r[0]).name() : "TAKE_AWAY";
            long       orders  = (Long) r[1];
            BigDecimal revenue = (BigDecimal) r[2];
            long       items   = itemMap.getOrDefault(src, 0L);
            if (statMap.containsKey(src)) {
                statMap.put(src, PosDashboardDto.PosSourceStat.builder()
                        .source(src).totalItems(items).totalOrders(orders)
                        .totalRevenue(revenue).build());
            }
        }
        return new ArrayList<>(statMap.values());
    }

    // ══════════════════════════════════════════════════════════
    // POS — PAYMENT BREAKDOWN
    // ══════════════════════════════════════════════════════════

    private static String posPaymentLabel(String method) {
        return switch (method) {
            case "CASH" -> "Tiền mặt"; case "BANK_TRANSFER" -> "Chuyển khoản";
            case "MOMO" -> "MoMo"; case "VNPAY" -> "VNPay"; case "ZALOPAY" -> "ZaloPay";
            default -> method;
        };
    }

    private PosDashboardDto.PosPaymentBreakdown getPosPaymentBreakdown(Long from, Long to, Long storeId) {
        // 1. Payment methods
        List<Object[]> rows = em.createQuery(
                        "SELECT po.paymentMethod, COUNT(po), COALESCE(SUM(po.finalAmount), 0) " +
                                "FROM PosOrder po WHERE po.status = :status " +
                                "  AND (:from IS NULL OR po.createdAt >= :from) " +
                                "  AND (:to IS NULL OR po.createdAt <= :to) " +
                                "  AND (:storeId IS NULL OR po.store.id = :storeId) " +
                                "GROUP BY po.paymentMethod ORDER BY SUM(po.finalAmount) DESC", Object[].class)
                .setParameter("status", PosOrderStatus.COMPLETED)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("storeId", storeId).getResultList();

        List<PosDashboardDto.PosPaymentMethodItem> methods = rows.stream().map(r ->
                        PosDashboardDto.PosPaymentMethodItem.builder()
                                .method(r[0] != null ? r[0].toString() : "OTHER")
                                .label(posPaymentLabel(r[0] != null ? r[0].toString() : "OTHER"))
                                .amount((BigDecimal) r[2]).count((Long) r[1]).build())
                .collect(Collectors.toList());

        // 2. Source pie
        List<Object[]> srcRows = em.createQuery(
                        "SELECT po.orderSource, COUNT(po), COALESCE(SUM(po.finalAmount), 0) " +
                                "FROM PosOrder po " +
                                "WHERE (:from IS NULL OR po.createdAt >= :from) " +
                                "  AND (:to IS NULL OR po.createdAt <= :to) " +
                                "  AND (:storeId IS NULL OR po.store.id = :storeId) " +
                                "GROUP BY po.orderSource", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("storeId", storeId).getResultList();

        List<PosDashboardDto.PosPieItem> sourcePie = srcRows.stream().map(r -> {
            String key   = r[0] != null ? ((Enum<?>) r[0]).name() : "TAKE_AWAY";
            String label = switch (key) {
                case "SHOPEE_FOOD" -> "ShopeeFood"; case "GRAB_FOOD" -> "GrabFood";
                case "DINE_IN" -> "Dine In"; default -> "Take Away";
            };
            return PosDashboardDto.PosPieItem.builder()
                    .key(key).label(label).count((Long) r[1]).amount((BigDecimal) r[2]).build();
        }).collect(Collectors.toList());

        // 3. Category pie
        // ── FIX: COUNT(poi.id) thay vì SUM(poi.quantity) ──────────────────
        List<Object[]> catRows = em.createQuery(
                        "SELECT poi.categoryName, " +
                                "       COUNT(DISTINCT poi.order.id), " +   // số đơn có món thuộc category
                                "       COUNT(poi.id), " +                  // ← FIX: số món (không tính quantity)
                                "       COALESCE(SUM(poi.subtotal), 0) " +  // doanh thu
                                "FROM PosOrderItem poi " +
                                "WHERE (:from IS NULL OR poi.order.createdAt >= :from) " +
                                "  AND (:to IS NULL OR poi.order.createdAt <= :to) " +
                                "  AND (:storeId IS NULL OR poi.order.store.id = :storeId) " +
                                "GROUP BY poi.categoryName", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("storeId", storeId).getResultList();

        long hotCount = 0, coldCount = 0, comboCount = 0;
        long hotItems = 0, coldItems = 0, comboItems = 0;
        BigDecimal hotAmt = BigDecimal.ZERO, coldAmt = BigDecimal.ZERO, comboAmt = BigDecimal.ZERO;

        for (Object[] r : catRows) {
            String     catName = r[0] != null ? r[0].toString().trim() : "";
            long       cnt     = (Long) r[1];         // số đơn hàng
            long       items   = (Long) r[2];          // ← FIX: COUNT trả Long trực tiếp
            BigDecimal amt     = (BigDecimal) r[3];
            if (catName.equalsIgnoreCase("Lạnh")) {
                coldCount += cnt; coldItems += items; coldAmt = coldAmt.add(amt);
            } else if (catName.equalsIgnoreCase("Combo")) {
                comboCount += cnt; comboItems += items; comboAmt = comboAmt.add(amt);
            } else {
                hotCount += cnt; hotItems += items; hotAmt = hotAmt.add(amt);
            }
        }

        List<PosDashboardDto.PosPieItem> categoryPie = List.of(
                PosDashboardDto.PosPieItem.builder().key("HOT").label("Nóng")
                        .count(hotCount).itemCount(hotItems).amount(hotAmt).build(),
                PosDashboardDto.PosPieItem.builder().key("COLD").label("Lạnh")
                        .count(coldCount).itemCount(coldItems).amount(coldAmt).build(),
                PosDashboardDto.PosPieItem.builder().key("COMBO").label("Combo")
                        .count(comboCount).itemCount(comboItems).amount(comboAmt).build()
        );

        return PosDashboardDto.PosPaymentBreakdown.builder()
                .methods(methods).sourcePieItems(sourcePie).categoryPieItems(categoryPie).build();
    }

    // ══════════════════════════════════════════════════════════
    // POS — TOP PRODUCTS
    // ══════════════════════════════════════════════════════════

    private List<PosDashboardDto.PosTopProduct> getPosTopProducts(Long from, Long to, int limit, Long storeId) {
        return em.createQuery(
                        "SELECT poi.productId, MAX(poi.productName), MAX(poi.productImageUrl), " +
                                "       SUM(poi.quantity), SUM(poi.subtotal), COUNT(DISTINCT poi.order.id) " +
                                "FROM PosOrderItem poi " +
                                "WHERE (:from IS NULL OR poi.order.createdAt >= :from) " +
                                "  AND (:to IS NULL OR poi.order.createdAt <= :to) " +
                                "  AND (:storeId IS NULL OR poi.order.store.id = :storeId) " +
                                "GROUP BY poi.productId ORDER BY SUM(poi.subtotal) DESC", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("storeId", storeId).setMaxResults(limit)
                .getResultList().stream().map(r -> PosDashboardDto.PosTopProduct.builder()
                        .productId((Long) r[0]).productName((String) r[1])
                        .productImageUrl((String) r[2])
                        .totalQuantity(r[3] != null ? new BigDecimal(r[3].toString()) : BigDecimal.ZERO)
                        .totalRevenue(r[4] != null ? (BigDecimal) r[4] : BigDecimal.ZERO)
                        .orderCount((Long) r[5]).build())
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════
    // POS — ORDERS BY TIME
    // ══════════════════════════════════════════════════════════

    private List<PosDashboardDto.PosOrderByTime> getPosOrdersByTime(
            Long from, Long to, String granularity, Long storeId, String filterType) {

        final boolean isCatFilter = filterType != null &&
                (filterType.equals("CAT_HOT") || filterType.equals("CAT_COLD") ||
                        filterType.equals("CAT_COMBO"));

        if (isCatFilter) {
            String catCondition = switch (filterType) {
                case "CAT_COLD"  -> "LOWER(poi.categoryName) = 'lạnh'";
                case "CAT_COMBO" -> "LOWER(poi.categoryName) = 'combo'";
                default          -> "LOWER(poi.categoryName) NOT IN ('lạnh', 'combo')";
            };

            List<Object[]> countRows = em.createQuery(
                            "SELECT po.createdAt, COUNT(DISTINCT po.id) " +
                                    "FROM PosOrderItem poi JOIN poi.order po " +
                                    "WHERE " + catCondition +
                                    "  AND (:from IS NULL OR po.createdAt >= :from) " +
                                    "  AND (:to IS NULL OR po.createdAt <= :to) " +
                                    "  AND (:storeId IS NULL OR po.store.id = :storeId) " +
                                    "GROUP BY po.createdAt ORDER BY po.createdAt ASC", Object[].class)
                    .setParameter("from", from).setParameter("to", to)
                    .setParameter("storeId", storeId).getResultList();

            List<Object[]> revRows = em.createQuery(
                            "SELECT po.createdAt, COALESCE(SUM(poi.subtotal), 0) " +
                                    "FROM PosOrderItem poi JOIN poi.order po " +
                                    "WHERE " + catCondition +
                                    "  AND (:from IS NULL OR po.createdAt >= :from) " +
                                    "  AND (:to IS NULL OR po.createdAt <= :to) " +
                                    "  AND (:storeId IS NULL OR po.store.id = :storeId) " +
                                    "GROUP BY po.createdAt ORDER BY po.createdAt ASC", Object[].class)
                    .setParameter("from", from).setParameter("to", to)
                    .setParameter("storeId", storeId).getResultList();

            Map<String, long[]>     countMap = new LinkedHashMap<>();
            Map<String, BigDecimal> revMap   = new LinkedHashMap<>();
            for (Object[] r : countRows) {
                String bucket = toBucket((Long) r[0], granularity);
                countMap.merge(bucket, new long[]{(Long) r[1]},
                        (a, b) -> new long[]{a[0] + b[0]});
            }
            for (Object[] r : revRows) {
                String     bucket = toBucket((Long) r[0], granularity);
                BigDecimal amt    = (BigDecimal) r[1];
                revMap.merge(bucket, amt != null ? amt : BigDecimal.ZERO, BigDecimal::add);
            }
            return buildOrdersByTime(countMap, revMap, from, to, granularity);

        } else {
            String sourceCondition = (filterType == null || filterType.equals("ALL"))
                    ? "" : "AND po.orderSource = :orderSource ";

            var query = em.createQuery(
                            "SELECT po.createdAt, po.finalAmount FROM PosOrder po " +
                                    "WHERE (:from IS NULL OR po.createdAt >= :from) " +
                                    "  AND (:to IS NULL OR po.createdAt <= :to) " +
                                    "  AND (:storeId IS NULL OR po.store.id = :storeId) " +
                                    sourceCondition + "ORDER BY po.createdAt ASC", Object[].class)
                    .setParameter("from", from).setParameter("to", to)
                    .setParameter("storeId", storeId);

            if (!sourceCondition.isEmpty()) {
                query.setParameter("orderSource",
                        com.nhatnam.server.enumtype.OrderSource.valueOf(filterType));
            }

            Map<String, long[]>     countMap = new LinkedHashMap<>();
            Map<String, BigDecimal> revMap   = new LinkedHashMap<>();
            for (Object[] r : query.getResultList()) {
                String bucket = toBucket((Long) r[0], granularity);
                countMap.computeIfAbsent(bucket, k -> new long[]{0})[0]++;
                revMap.merge(bucket, r[1] != null ? (BigDecimal) r[1] : BigDecimal.ZERO, BigDecimal::add);
            }
            return buildOrdersByTime(countMap, revMap, from, to, granularity);
        }
    }

    private List<PosDashboardDto.PosOrderByTime> buildOrdersByTime(
            Map<String, long[]> countMap, Map<String, BigDecimal> revMap,
            Long from, Long to, String granularity) {

        if (from != null && to != null) {
            List<String> allBuckets = generateAllBuckets(from, to, granularity);
            for (String b : allBuckets) {
                countMap.putIfAbsent(b, new long[]{0});
                revMap.putIfAbsent(b, BigDecimal.ZERO);
            }
            Map<String, long[]>     sorted    = new LinkedHashMap<>();
            Map<String, BigDecimal> sortedRev = new LinkedHashMap<>();
            for (String b : allBuckets) {
                sorted.put(b, countMap.get(b));
                sortedRev.put(b, revMap.get(b));
            }
            countMap = sorted; revMap = sortedRev;
        }

        final Map<String, long[]>     fc = countMap;
        final Map<String, BigDecimal> fr = revMap;
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(fc.entrySet());
        int last = entries.size() - 1;
        while (last >= 0 && entries.get(last).getValue()[0] == 0) last--;
        if (last < entries.size() - 1) entries = entries.subList(0, last + 1);

        return entries.stream().map(e -> {
            String     b   = e.getKey();
            long       cnt = e.getValue()[0];
            BigDecimal rev = fr.getOrDefault(b, BigDecimal.ZERO);
            BigDecimal aov = cnt > 0
                    ? rev.divide(BigDecimal.valueOf(cnt), 0, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return PosDashboardDto.PosOrderByTime.builder()
                    .timeBucket(b).orderCount(cnt).revenue(rev).aov(aov).build();
        }).collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════
    // POS — RECENT ORDERS
    // ══════════════════════════════════════════════════════════

    private List<PosDashboardDto.PosRecentOrder> getPosRecentOrders(Long from, Long to, int limit, Long storeId) {
        return em.createQuery(
                        "SELECT po.id, po.orderCode, po.orderSource, po.createdAt, " +
                                "       po.totalAmount, po.finalAmount, po.status, po.paymentMethod " +
                                "FROM PosOrder po " +
                                "WHERE (:from IS NULL OR po.createdAt >= :from) " +
                                "  AND (:to IS NULL OR po.createdAt <= :to) " +
                                "  AND (:storeId IS NULL OR po.store.id = :storeId) " +
                                "ORDER BY po.createdAt DESC", Object[].class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("storeId", storeId).setMaxResults(limit)
                .getResultList().stream().map(r -> PosDashboardDto.PosRecentOrder.builder()
                        .orderId((Long) r[0]).orderCode((String) r[1])
                        .orderSource(r[2] != null ? ((Enum<?>) r[2]).name() : null)
                        .createdAt((Long) r[3])
                        .totalAmount(r[4] != null ? (BigDecimal) r[4] : BigDecimal.ZERO)
                        .finalAmount(r[5] != null ? (BigDecimal) r[5] : BigDecimal.ZERO)
                        .status(r[6] != null ? ((Enum<?>) r[6]).name() : null)
                        .paymentMethod(r[7] != null ? r[7].toString() : null)
                        .paymentStatus(r[6] != null && ((Enum<?>) r[6]).name().equals("COMPLETED")
                                ? "PAID" : "UNPAID").build())
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════
    // SHARED HELPERS
    // ══════════════════════════════════════════════════════════

    private List<String> generateAllBuckets(Long from, Long to, String granularity) {
        List<String> buckets = new ArrayList<>();
        LocalDate start = Instant.ofEpochMilli(from).atZone(VN_ZONE).toLocalDate();
        LocalDate end   = Instant.ofEpochMilli(to).atZone(VN_ZONE).toLocalDate();
        switch (granularity.toUpperCase()) {
            case "DAY" -> {
                LocalDate cur = start;
                while (!cur.isAfter(end)) {
                    buckets.add(cur.format(DateTimeFormatter.ISO_LOCAL_DATE));
                    cur = cur.plusDays(1);
                }
            }
            case "WEEK" -> {
                LocalDate cur = start.with(java.time.temporal.WeekFields.ISO.dayOfWeek(), 1);
                while (!cur.isAfter(end)) {
                    buckets.add(cur.getYear() + "-W" + String.format("%02d",
                            cur.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())));
                    cur = cur.plusWeeks(1);
                }
            }
            case "MONTH" -> {
                LocalDate cur = start.withDayOfMonth(1);
                while (!cur.isAfter(end)) {
                    buckets.add(cur.format(DateTimeFormatter.ofPattern("yyyy-MM")));
                    cur = cur.plusMonths(1);
                }
            }
            case "YEAR" -> {
                int y = start.getYear();
                while (y <= end.getYear()) { buckets.add(String.valueOf(y)); y++; }
            }
            default -> {
                LocalDate cur = start;
                while (!cur.isAfter(end)) {
                    buckets.add(cur.format(DateTimeFormatter.ISO_LOCAL_DATE));
                    cur = cur.plusDays(1);
                }
            }
        }
        return buckets;
    }

    private String toBucket(Long epochMs, String granularity) {
        if (epochMs == null) return "unknown";
        LocalDate date = Instant.ofEpochMilli(epochMs).atZone(VN_ZONE).toLocalDate();
        return switch (granularity.toUpperCase()) {
            case "DAY"   -> date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            case "WEEK"  -> date.getYear() + "-W" + String.format("%02d",
                    date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));
            case "MONTH" -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            case "YEAR"  -> String.valueOf(date.getYear());
            default      -> date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        };
    }
}