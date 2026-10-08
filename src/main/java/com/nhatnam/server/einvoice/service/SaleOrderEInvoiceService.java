package com.nhatnam.server.einvoice.service;

import com.nhatnam.server.einvoice.ViettelEInvoiceConfig;
import com.nhatnam.server.einvoice.dto.EInvoiceRequestDto.BusinessBuyerInfo;
import com.nhatnam.server.einvoice.dto.EInvoiceRequestDto.EInvoiceResult;
import com.nhatnam.server.einvoice.dto.ViettelInvoiceDto.*;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.util.NumberToWordsVN;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dựng hóa đơn điện tử Viettel cho ĐƠN SỈ/LẺ (entity {@link Order}).
 *
 * Khác biệt quan trọng so với đơn POS:
 *   • POS  : giá món đã BAO GỒM VAT  → phải tách ngược ra.
 *   • Sỉ/lẻ: OrderItem.unitPrice CHƯA gồm VAT, chiết khấu áp ở cấp đơn (%),
 *            VAT tính trên số tiền SAU chiết khấu (xem OrderServiceImpl).
 *
 * Vì vậy ở đây chiết khấu được phân bổ về từng dòng (itemDiscount) thay vì
 * tạo thêm một dòng "chiết khấu tổng" như bên POS — cách này khớp với cách
 * VAT đã được tính và lưu trong DB.
 *
 * Toàn bộ phần ký số / gọi API Viettel được tái sử dụng từ
 * {@link ViettelEInvoiceService#issuePreparedInvoice} và
 * {@link ViettelEInvoiceService#previewPreparedInvoice}.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class SaleOrderEInvoiceService {

    private final ViettelEInvoiceService einvoiceService;
    private final ViettelEInvoiceConfig config;

    /** Chênh lệch làm tròn tối đa (VND) còn được phép tự động bù vào tiền thuế. */
    private static final long MAX_ROUNDING_FIX = 1000L;

    // ════════════════════════════════════════════════════════════════
    // PUBLIC
    // ════════════════════════════════════════════════════════════════

    /** Phát hành hóa đơn cho đơn sỉ/lẻ. buyerOverride = null → lấy thông tin từ đơn. */
    public EInvoiceResult issueInvoice(Order order, BusinessBuyerInfo buyerOverride) {
        return einvoiceService.issuePreparedInvoice(
                buildInvoiceRequest(order, resolveBuyer(order, buyerOverride)));
    }

    /** Xem trước PDF (base64), không phát hành, không lưu DB. */
    public String previewInvoice(Order order, BusinessBuyerInfo buyerOverride) {
        return einvoiceService.previewPreparedInvoice(
                buildInvoiceRequest(order, resolveBuyer(order, buyerOverride)));
    }

    // ════════════════════════════════════════════════════════════════
    // Người mua
    // ════════════════════════════════════════════════════════════════

    /**
     * Ưu tiên thông tin FE gửi lên, thiếu field nào thì lấy từ đơn hàng.
     * Có MST  → hóa đơn doanh nghiệp (buyerNotGetInvoice = "0", có buyerTaxCode).
     * Không   → hóa đơn cá nhân, vẫn ghi tên KH (buyerNotGetInvoice = "0").
     */
    public BuyerInfo resolveBuyer(Order order, BusinessBuyerInfo o) {
        String taxCode = firstNonBlank(o != null ? o.getTaxCode() : null, order.getTaxCode());
        String company = firstNonBlank(o != null ? o.getCompanyName() : null,
                order.getCompanyName(), order.getShortName());
        String address = firstNonBlank(o != null ? o.getAddress() : null,
                order.getCompanyAddress(), order.getDeliveryAddress(), order.getShippingAddress());
        String email = firstNonBlank(o != null ? o.getEmail() : null,
                order.getInvoiceEmail(), order.getCustomerEmail());
        String phone = firstNonBlank(o != null ? o.getPhone() : null,
                order.getCompanyPhone(), order.getCustomerPhone());
        String person = firstNonBlank(order.getContactName(), order.getCustomerName());

        if (isBlank(taxCode)) {
            // Khách lẻ / cá nhân — không có MST
            return BuyerInfo.builder()
                    .buyerName(firstNonBlank(person, "Khách lẻ"))
                    .buyerAddressLine(address)
                    .buyerEmail(email)
                    .buyerPhoneNumber(phone)
                    .buyerNotGetInvoice("0")
                    .build();
        }

        return BuyerInfo.builder()
                .buyerName(person)
                .buyerLegalName(firstNonBlank(company, person))
                .buyerTaxCode(taxCode.trim())
                .buyerAddressLine(address)
                .buyerEmail(email)
                .buyerPhoneNumber(phone)
                .buyerNotGetInvoice("0")
                .build();
    }

    // ════════════════════════════════════════════════════════════════
    // Build request
    // ════════════════════════════════════════════════════════════════

    public CreateInvoiceRequest buildInvoiceRequest(Order order, BuyerInfo buyer) {
        List<Line> lines = buildLines(order);

        GeneralInvoiceInfo generalInfo = GeneralInvoiceInfo.builder()
                // Đơn sỉ/lẻ → dải hóa đơn hiện tại (không dùng dải máy tính tiền)
                .invoiceType(config.invoiceTypeFor(false))
                .templateCode(config.templateCodeFor(false))
                .invoiceSeries(config.invoiceSeriesFor(false))
                .currencyCode("VND")
                .exchangeRate(1)
                .adjustmentType("1")
                .paymentStatus(true)
                .cusGetInvoiceRight(true)
                .invoiceIssuedDate(null)
                .transactionUuid(null)
                .build();

        List<PaymentMethod> payments = List.of(
                PaymentMethod.builder()
                        .paymentMethod(mapPaymentMethodCode(order.getPaymentMethod()))
                        .paymentMethodName(mapPaymentMethodName(order.getPaymentMethod()))
                        .build());

        return CreateInvoiceRequest.builder()
                .generalInvoiceInfo(generalInfo)
                .buyerInfo(buyer)
                .payments(payments)
                .itemInfo(toItemInfo(lines))
                .taxBreakdowns(toTaxBreakdowns(lines))
                .summarizeInfo(toSummarizeInfo(order, lines))
                .metadata(List.of(Metadata.builder()
                        .keyTag("invoiceNote")
                        .stringValue(order.getNotes() != null ? order.getNotes() : "")
                        .valueType("text")
                        .keyLabel("Ghi chú")
                        .build()))
                .build();
    }

    // ════════════════════════════════════════════════════════════════
    // Tính tiền từng dòng
    // ════════════════════════════════════════════════════════════════

    /** Một dòng hàng đã quy về số nguyên VND, đã phân bổ chiết khấu và thuế. */
    private static final class Line {
        OrderItem  src;
        int        pct;                  // thuế suất thực (0/5/8/10)
        BigDecimal qty;
        BigDecimal unitPrice;            // chưa VAT
        BigDecimal amountNoTax;          // qty * unitPrice, trước CK
        BigDecimal itemDiscount;         // tiền CK phân bổ cho dòng
        BigDecimal amountAfterDiscount;  // amountNoTax - itemDiscount
        BigDecimal taxAmount;
    }

    /**
     * 1. Làm tròn từng dòng về VND nguyên.
     * 2. Phân bổ chiết khấu tổng theo tỷ trọng, phần dư dồn vào dòng cuối
     *    → Σ itemDiscount == chiết khấu của đơn.
     * 3. Gom nhóm theo thuế suất, tính thuế trên tiền SAU chiết khấu của nhóm,
     *    rồi rải ngược về từng dòng → Σ taxAmount của nhóm khớp tuyệt đối.
     * 4. Bù chênh lệch làm tròn (nếu có) để tổng tiền thanh toán trên hóa đơn
     *    đúng bằng finalAmount khách đã trả.
     */
    private List<Line> buildLines(Order order) {
        List<OrderItem> items = order.getOrderItems();
        if (items == null || items.isEmpty())
            throw new IllegalStateException("Đơn hàng không có dòng hàng nào để xuất hóa đơn");

        List<Line> lines = new ArrayList<>();
        BigDecimal sumNoTax = BigDecimal.ZERO;

        for (OrderItem it : items) {
            Line l = new Line();
            l.src         = it;
            l.pct         = it.getVatRate() != null ? it.getVatRate() : 0;
            l.qty         = nz(it.getQuantity());
            l.amountNoTax = vnd(nz(it.getSubtotal()));
            // unitPrice suy ngược từ amount đã làm tròn để quantity × unitPrice khớp
            l.unitPrice   = l.qty.compareTo(BigDecimal.ZERO) > 0
                    ? l.amountNoTax.divide(l.qty, 2, RoundingMode.HALF_UP)
                    : vnd(nz(it.getUnitPrice()));
            l.itemDiscount        = BigDecimal.ZERO;
            l.amountAfterDiscount = l.amountNoTax;
            l.taxAmount           = BigDecimal.ZERO;
            lines.add(l);
            sumNoTax = sumNoTax.add(l.amountNoTax);
        }

        // ── B2: phân bổ chiết khấu ──────────────────────────────────
        BigDecimal totalDiscount = vnd(nz(order.getDiscountAmount()));
        if (totalDiscount.compareTo(BigDecimal.ZERO) > 0
                && sumNoTax.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < lines.size(); i++) {
                Line l = lines.get(i);
                BigDecimal d = (i == lines.size() - 1)
                        ? totalDiscount.subtract(allocated)                 // dòng cuối ôm phần dư
                        : vnd(totalDiscount.multiply(l.amountNoTax)
                        .divide(sumNoTax, 10, RoundingMode.HALF_UP));
                l.itemDiscount        = d;
                l.amountAfterDiscount = l.amountNoTax.subtract(d);
                allocated = allocated.add(d);
            }
        }

        // ── B3: thuế theo nhóm thuế suất ────────────────────────────
        Map<Integer, List<Line>> byRate = new LinkedHashMap<>();
        for (Line l : lines) byRate.computeIfAbsent(l.pct, k -> new ArrayList<>()).add(l);

        for (Map.Entry<Integer, List<Line>> e : byRate.entrySet()) {
            int pct = e.getKey();
            List<Line> group = e.getValue();
            if (pct <= 0) continue;                                 // không chịu thuế

            BigDecimal taxable = BigDecimal.ZERO;
            for (Line l : group) taxable = taxable.add(l.amountAfterDiscount);

            BigDecimal groupTax = vnd(taxable.multiply(BigDecimal.valueOf(pct))
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));

            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < group.size(); i++) {
                Line l = group.get(i);
                BigDecimal t = (i == group.size() - 1)
                        ? groupTax.subtract(allocated)
                        : (taxable.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : vnd(groupTax.multiply(l.amountAfterDiscount)
                        .divide(taxable, 10, RoundingMode.HALF_UP)));
                l.taxAmount = t;
                allocated = allocated.add(t);
            }
        }

        reconcileWithFinalAmount(order, lines);
        return lines;
    }

    /**
     * Bù chênh lệch làm tròn để tổng tiền thanh toán trên hóa đơn == finalAmount.
     * Chênh lệch chỉ nên là vài đồng; nếu lớn hơn ngưỡng thì ghi log cảnh báo
     * và giữ nguyên số tính được (không tự ý sửa số tiền lớn).
     */
    private void reconcileWithFinalAmount(Order order, List<Line> lines) {
        BigDecimal computed = BigDecimal.ZERO;
        for (Line l : lines) computed = computed.add(l.amountAfterDiscount).add(l.taxAmount);

        BigDecimal expected = vnd(nz(order.getFinalAmount()));
        BigDecimal delta    = expected.subtract(computed);
        if (delta.compareTo(BigDecimal.ZERO) == 0) return;

        if (delta.abs().longValue() > MAX_ROUNDING_FIX) {
            log.warn("[EInvoice][Sale] {} — lệch {} VND giữa finalAmount({}) và tổng tính lại({}), giữ nguyên số tính lại",
                    order.getOrderCode(), delta, expected, computed);
            return;
        }

        // Dồn chênh lệch vào dòng chịu thuế cuối cùng
        for (int i = lines.size() - 1; i >= 0; i--) {
            Line l = lines.get(i);
            if (l.pct > 0) {
                l.taxAmount = l.taxAmount.add(delta);
                log.info("[EInvoice][Sale] {} — bù {} VND làm tròn vào thuế dòng #{}",
                        order.getOrderCode(), delta, i + 1);
                return;
            }
        }
        // Không có dòng chịu thuế → bù vào tiền hàng dòng cuối
        Line last = lines.get(lines.size() - 1);
        last.amountAfterDiscount = last.amountAfterDiscount.add(delta);
        log.info("[EInvoice][Sale] {} — bù {} VND làm tròn vào tiền hàng dòng cuối",
                order.getOrderCode(), delta);
    }

    // ════════════════════════════════════════════════════════════════
    // Map sang DTO Viettel
    // ════════════════════════════════════════════════════════════════

    private List<ItemInfo> toItemInfo(List<Line> lines) {
        List<ItemInfo> result = new ArrayList<>();
        int no = 1;
        for (Line l : lines) {
            OrderItem it = l.src;
            String name = it.getProductName();
            if (!isBlank(it.getVariantName())) name = name + " - " + it.getVariantName();

            result.add(ItemInfo.builder()
                    .lineNumber(no++)
                    .selection(1)
                    .itemName(name)
                    .unitName(firstNonBlank(it.getUnit(), "cái"))
                    .quantity(l.qty)
                    .unitPrice(l.unitPrice)
                    .itemTotalAmountWithoutTax(l.amountNoTax)
                    .itemTotalAmountAfterDiscount(l.amountAfterDiscount)
                    .itemTotalAmountWithTax(l.amountAfterDiscount.add(l.taxAmount))
                    .itemDiscount(l.itemDiscount.compareTo(BigDecimal.ZERO) > 0 ? l.itemDiscount : null)
                    .taxPercentage(viettelTaxCode(l.pct))
                    .taxAmount(l.taxAmount)
                    .isIncreaseItem(null)
                    .build());
        }
        return result;
    }

    private List<TaxBreakdown> toTaxBreakdowns(List<Line> lines) {
        Map<Integer, BigDecimal[]> groups = new LinkedHashMap<>();
        for (Line l : lines) {
            groups.merge(viettelTaxCode(l.pct),
                    new BigDecimal[]{l.amountAfterDiscount, l.taxAmount},
                    (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
        }
        List<TaxBreakdown> out = new ArrayList<>();
        groups.forEach((pct, v) -> out.add(TaxBreakdown.builder()
                .taxPercentage(pct).taxableAmount(v[0]).taxAmount(v[1]).build()));
        return out;
    }

    private SummarizeInfo toSummarizeInfo(Order order, List<Line> lines) {
        BigDecimal sumNoTax   = BigDecimal.ZERO;
        BigDecimal afterDisc  = BigDecimal.ZERO;
        BigDecimal totalTax   = BigDecimal.ZERO;
        BigDecimal totalDisc  = BigDecimal.ZERO;

        for (Line l : lines) {
            sumNoTax  = sumNoTax.add(l.amountNoTax);
            afterDisc = afterDisc.add(l.amountAfterDiscount);
            totalTax  = totalTax.add(l.taxAmount);
            totalDisc = totalDisc.add(l.itemDiscount);
        }
        BigDecimal totalWithTax = afterDisc.add(totalTax);

        return SummarizeInfo.builder()
                .sumOfTotalLineAmountWithoutTax(sumNoTax)
                .totalAmountAfterDiscount(afterDisc)
                .totalAmountWithoutTax(afterDisc)
                .totalTaxAmount(totalTax)
                .totalAmountWithTax(totalWithTax)
                .totalAmountWithTaxInWords(NumberToWordsVN.convert(totalWithTax.longValue()))
                .discountAmount(totalDisc.compareTo(BigDecimal.ZERO) > 0 ? totalDisc : null)
                .build();
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    /** Viettel: -2 = không chịu thuế. Thuế suất 0 của đơn sỉ/lẻ hiểu là không chịu thuế. */
    private int viettelTaxCode(int pct) {
        return pct <= 0 ? -2 : pct;
    }

    private static BigDecimal vnd(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(0, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (!isBlank(v)) return v.trim();
        return null;
    }

    private String mapPaymentMethodName(String m) {
        if (m == null) return "TM";
        return switch (m.toUpperCase()) {
            case "CASH", "TIENMAT" -> "TM";
            case "BANK_TRANSFER", "TRANSFER", "CARD", "MOMO", "VNPAY", "ZALOPAY" -> "CK";
            default -> "TM/CK";
        };
    }

    private String mapPaymentMethodCode(String m) {
        if (m == null) return "1";
        return switch (m.toUpperCase()) {
            case "CASH", "TIENMAT" -> "1";
            case "BANK_TRANSFER", "TRANSFER", "MOMO", "VNPAY", "ZALOPAY", "CARD" -> "2";
            default -> "1";
        };
    }
}
