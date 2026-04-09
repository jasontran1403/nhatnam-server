package com.nhatnam.server.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class SellerOrderExportService {

    @PersistenceContext
    private EntityManager em;

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Cấu trúc cột cố định (20 cột, index 0–19):
    // 0  STT
    // 1  Mã đơn hàng
    // 2  Tên người nhận
    // 3  Địa chỉ nhận hàng
    // 4  SĐT
    // 5  Tổng tiền
    // 6  Giảm giá
    // 7  Thuế VAT
    // 8  Thành tiền
    // 9  Thời gian tạo
    // 10 Thanh toán
    // 11 Tên công ty   ← luôn hiển thị; merge 11-13 + "-" nếu không có data
    // 12 Mã số thuế
    // 13 Địa chỉ công ty
    // 14 Tên sản phẩm
    // 15 Đơn vị
    // 16 Số lượng
    // 17 Giá gốc
    // 18 Giá bán
    // 19 VAT %
    private static final int LAST_COL = 19;

    // ── DTOs ─────────────────────────────────────────────────────

    record OrderRow(
            Long       orderId,
            String     orderCode,
            String     customerName,
            String     customerPhone,
            String     shippingAddress,
            BigDecimal subtotal,
            BigDecimal discountAmount,
            BigDecimal vatAmount,
            BigDecimal finalAmount,
            Long       createdAt,
            String     paymentMethod,
            String     companyName,
            String     taxCode,
            String     companyAddress
    ) {
        boolean hasCompany() {
            return companyName != null && !companyName.isBlank();
        }
    }

    record ItemRow(
            Long       orderId,
            String     productName,
            String     unit,
            BigDecimal quantity,
            BigDecimal basePrice,
            BigDecimal unitPrice,
            BigDecimal vatRate
    ) {}

    // ── Public API ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] exportRetail(Long fromMs, Long toMs) {
        return export(fromMs, toMs, "RETAIL");
    }

    @Transactional(readOnly = true)
    public byte[] exportWholesale(Long fromMs, Long toMs) {
        return export(fromMs, toMs, "WHOLESALE");
    }

    @Transactional(readOnly = true)
    public byte[] exportRestaurantOrders(Long fromMs, Long toMs, String mode) {
        String type = "wholesale".equalsIgnoreCase(mode) ? "WHOLESALE" : "RETAIL";
        return export(fromMs, toMs, type);
    }

    // ── Core ──────────────────────────────────────────────────────

    private byte[] export(Long fromMs, Long toMs, String orderType) {
        List<Object[]> orderRows = em.createQuery(
                        "SELECT o.id, o.orderCode, o.customerName, o.customerPhone, " +
                                "       o.shippingAddress, o.subtotal, o.discountAmount, " +
                                "       o.vatAmount, o.finalAmount, o.createdAt, o.paymentMethod, " +
                                "       o.companyName, o.taxCode, o.companyAddress " +
                                "FROM Order o " +
                                "WHERE o.type = :type " +
                                "  AND (:from IS NULL OR o.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR o.createdAt <= :to) " +
                                "  AND o.status = 'COMPLETED' " +
                                "ORDER BY o.createdAt ASC", Object[].class)
                .setParameter("type", orderType)
                .setParameter("from", fromMs)
                .setParameter("to", toMs)
                .getResultList();

        List<Object[]> itemRows = em.createQuery(
                        "SELECT oi.order.id, oi.productName, oi.unit, " +
                                "       oi.quantity, oi.basePrice, oi.unitPrice, oi.vatRate " +
                                "FROM OrderItem oi " +
                                "WHERE oi.order.type = :type " +
                                "  AND (:from IS NULL OR oi.order.createdAt >= :from) " +
                                "  AND (:to   IS NULL OR oi.order.createdAt <= :to) " +
                                "  AND oi.order.status = 'COMPLETED' " +
                                "ORDER BY oi.order.createdAt ASC, oi.id ASC", Object[].class)
                .setParameter("type", orderType)
                .setParameter("from", fromMs)
                .setParameter("to", toMs)
                .getResultList();

        Map<Long, List<ItemRow>> itemMap = new LinkedHashMap<>();
        for (Object[] r : itemRows) {
            Long oid = (Long) r[0];
            itemMap.computeIfAbsent(oid, k -> new ArrayList<>())
                    .add(new ItemRow(oid, nvl(r[1]), nvl(r[2]),
                            toBD(r[3]), toBD(r[4]), toBD(r[5]), toBD(r[6])));
        }

        List<OrderRow> orders = orderRows.stream().map(r -> new OrderRow(
                (Long) r[0], nvl(r[1]), nvl(r[2]), nvl(r[3]), nvl(r[4]),
                toBD(r[5]), toBD(r[6]), toBD(r[7]), toBD(r[8]),
                (Long) r[9], nvl(r[10]),
                nvl(r[11]), nvl(r[12]), nvl(r[13])
        )).collect(Collectors.toList());

        boolean isWholesale = "WHOLESALE".equals(orderType);
        return buildExcel(orders, itemMap, fromMs, toMs, isWholesale);
    }

    // ── Excel builder ─────────────────────────────────────────────

    private byte[] buildExcel(List<OrderRow> orders,
                              Map<Long, List<ItemRow>> itemMap,
                              Long fromMs, Long toMs,
                              boolean isWholesale) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Orders");

            CellStyle titleStyle    = makeTitleStyle(wb);
            CellStyle subtitleStyle = makeSubtitleStyle(wb);
            CellStyle headerStyle   = makeHeaderStyle(wb);
            CellStyle dataStyle     = makeDataStyle(wb);
            CellStyle numStyle      = makeNumStyle(wb);

            setColumnWidths(sheet);

            int rowNum = 0;

            // ── Title ─────────────────────────────────────────────
            Row titleRow = sheet.createRow(rowNum++);
            titleRow.setHeightInPoints(32);
            Cell tc = titleRow.createCell(0);
            tc.setCellValue("BÁO CÁO ĐƠN HÀNG " + (isWholesale ? "SỈ" : "LẺ"));
            tc.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, LAST_COL));

            // ── Subtitle ──────────────────────────────────────────
            Row dateRow = sheet.createRow(rowNum++);
            Cell dc = dateRow.createCell(0);
            dc.setCellValue("Khoảng thời gian: " + fmtDate(fromMs) + " – " + fmtDate(toMs)
                    + "   |   Tổng số đơn: " + orders.size());
            dc.setCellStyle(subtitleStyle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, LAST_COL));
            rowNum++; // blank row

            // ── Headers ───────────────────────────────────────────
            Row hRow = sheet.createRow(rowNum++);
            hRow.setHeightInPoints(24);
            String[] headers = {
                    "STT", "Mã đơn hàng", "Tên người nhận", "Địa chỉ nhận hàng",
                    "SĐT", "Tổng tiền", "Giảm giá", "Thuế VAT", "Thành tiền",
                    "Thời gian tạo", "Thanh toán",
                    "Tên công ty", "Mã số thuế", "Địa chỉ công ty",
                    "Tên sản phẩm", "Đơn vị", "Số lượng",
                    "Giá gốc", "Giá bán", "VAT %"
            };
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            // ── Data rows ─────────────────────────────────────────
            int stt = 1;
            for (OrderRow order : orders) {
                List<ItemRow> items    = itemMap.getOrDefault(order.orderId(), List.of());
                int itemCount          = Math.max(items.size(), 1);
                int orderStartRow      = rowNum;
                boolean hasCompany     = order.hasCompany();

                for (int ii = 0; ii < itemCount; ii++) {
                    Row row = sheet.createRow(rowNum++);
                    row.setHeightInPoints(18);

                    if (ii == 0) {
                        // Cột đơn hàng (0–10)
                        setL(row,  0, String.valueOf(stt++),             dataStyle);
                        setL(row,  1, order.orderCode(),                 dataStyle);
                        setL(row,  2, nullDash(order.customerName()),    dataStyle);
                        setL(row,  3, nullDash(order.shippingAddress()), dataStyle);
                        setL(row,  4, nullDash(order.customerPhone()),   dataStyle);
                        setN(row,  5, order.subtotal(),                  numStyle);
                        setN(row,  6, order.discountAmount(),            numStyle);
                        setN(row,  7, order.vatAmount(),                 numStyle);
                        setN(row,  8, order.finalAmount(),               numStyle);
                        setL(row,  9, fmtDateTime(order.createdAt()),    dataStyle);
                        setL(row, 10, pmLabel(order.paymentMethod()),    dataStyle);

                        // Cột công ty (11–13)
                        if (hasCompany) {
                            // Có data → hiển thị 3 ô riêng
                            setL(row, 11, nullDash(order.companyName()),   dataStyle);
                            setL(row, 12, nullDash(order.taxCode()),        dataStyle);
                            setL(row, 13, nullDash(order.companyAddress()), dataStyle);
                        } else {
                            // Không có → ô 11 chứa "-", 12 và 13 trống
                            // Merge được thực hiện SAU vòng lặp để tránh overlap
                            setL(row, 11, "-", dataStyle);
                            row.createCell(12).setCellStyle(dataStyle);
                            row.createCell(13).setCellStyle(dataStyle);
                        }
                    } else {
                        // Dòng phụ (item thứ 2 trở đi) — để trống cột 0–13
                        for (int bc = 0; bc <= 13; bc++) {
                            row.createCell(bc).setCellStyle(dataStyle);
                        }
                    }

                    // Cột item (14–19)
                    if (ii < items.size()) {
                        ItemRow item = items.get(ii);
                        setL(row, 14, nvl(item.productName()),    dataStyle);
                        setL(row, 15, nvl(item.unit()),           dataStyle);
                        setN(row, 16, item.quantity(),            numStyle);
                        setN(row, 17, item.basePrice(),           numStyle);
                        setN(row, 18, item.unitPrice(),           numStyle);
                        setL(row, 19, fmtVatRate(item.vatRate()), dataStyle);
                    } else {
                        for (int bc = 14; bc <= 19; bc++) {
                            row.createCell(bc).setCellStyle(dataStyle);
                        }
                    }
                }

                // Merge cột đơn hàng (0–10) theo chiều dọc nếu nhiều item
                if (itemCount > 1) {
                    int mergeEnd = orderStartRow + itemCount - 1;
                    // Merge cột 0–10 theo chiều dọc
                    for (int mc = 0; mc <= 10; mc++) {
                        sheet.addMergedRegion(
                                new CellRangeAddress(orderStartRow, mergeEnd, mc, mc));
                    }
                    if (hasCompany) {
                        // Có công ty → merge từng cột 11, 12, 13 theo chiều dọc
                        for (int mc = 11; mc <= 13; mc++) {
                            sheet.addMergedRegion(
                                    new CellRangeAddress(orderStartRow, mergeEnd, mc, mc));
                        }
                    } else {
                        // Không có công ty + nhiều item → merge dọc-ngang 11–13
                        sheet.addMergedRegion(
                                new CellRangeAddress(orderStartRow, mergeEnd, 11, 13));
                    }
                } else {
                    // Chỉ 1 item: nếu không có công ty → merge ngang 11–13
                    if (!hasCompany) {
                        sheet.addMergedRegion(
                                new CellRangeAddress(orderStartRow, orderStartRow, 11, 13));
                    }
                }
            }

            // Auto size
            for (int i = 0; i <= LAST_COL; i++) {
                sheet.autoSizeColumn(i, true);
                sheet.setColumnWidth(i, Math.max(sheet.getColumnWidth(i) + 4 * 256, 10 * 256));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            log.error("[SellerExport] buildExcel error", e);
            throw new RuntimeException("Lỗi tạo file Excel: " + e.getMessage(), e);
        }
    }

    // ── Column widths ─────────────────────────────────────────────

    private void setColumnWidths(XSSFSheet sheet) {
        int[] widths = {
                6,   // 0  STT
                24,  // 1  Mã đơn
                20,  // 2  Tên người nhận
                32,  // 3  Địa chỉ nhận
                14,  // 4  SĐT
                14,  // 5  Tổng tiền
                12,  // 6  Giảm giá
                10,  // 7  Thuế VAT
                14,  // 8  Thành tiền
                18,  // 9  Thời gian
                14,  // 10 Thanh toán
                24,  // 11 Tên công ty
                16,  // 12 MST
                28,  // 13 Địa chỉ CT
                28,  // 14 Tên SP
                8,   // 15 Đơn vị
                8,   // 16 SL
                12,  // 17 Giá gốc
                12,  // 18 Giá bán
                8    // 19 VAT%
        };
        for (int i = 0; i < widths.length; i++)
            sheet.setColumnWidth(i, widths[i] * 256);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private String fmtDateTime(Long ms) {
        if (ms == null) return "";
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), VN_ZONE).format(DT_FMT);
    }

    private String fmtDate(Long ms) {
        if (ms == null) return "";
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), VN_ZONE).format(DATE_FMT);
    }

    private String fmtVatRate(BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) return "0%";
        return rate.stripTrailingZeros().toPlainString() + "%";
    }

    private String pmLabel(String m) {
        if (m == null) return "Tiền mặt";
        return switch (m) {
            case "CASH"                     -> "Tiền mặt";
            case "BANK_TRANSFER","TRANSFER" -> "Chuyển khoản";
            case "MOMO"    -> "MoMo";
            case "VNPAY"   -> "VNPay";
            case "ZALOPAY" -> "ZaloPay";
            default        -> m;
        };
    }

    private BigDecimal toBD(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }

    private String nvl(Object o)      { return o != null ? o.toString() : ""; }
    private String nullDash(String s) { return (s != null && !s.isBlank()) ? s : "-"; }

    private void setL(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        c.setCellStyle(style);
    }

    private void setN(Row row, int col, BigDecimal value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value.doubleValue() : 0);
        c.setCellStyle(style);
    }

    // ── Cell styles ───────────────────────────────────────────────

    private CellStyle makeTitleStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setBold(true); f.setFontHeightInPoints((short) 16);
        f.setColor(new XSSFColor(new byte[]{(byte)255,(byte)255,(byte)255}, null));
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(
                new XSSFColor(new byte[]{(byte)13,(byte)148,(byte)136}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private CellStyle makeSubtitleStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setItalic(true); f.setFontHeightInPoints((short) 10);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.LEFT);
        return s;
    }

    private CellStyle makeHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setBold(true); f.setFontHeightInPoints((short) 11);
        f.setColor(new XSSFColor(new byte[]{(byte)255,(byte)255,(byte)255}, null));
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(
                new XSSFColor(new byte[]{(byte)15,(byte)118,(byte)110}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorder(s); s.setWrapText(true);
        return s;
    }

    private CellStyle makeDataStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(
                new XSSFColor(new byte[]{(byte)240,(byte)253,(byte)250}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorder(s);
        return s;
    }

    private CellStyle makeNumStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("#,##0"));
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(
                new XSSFColor(new byte[]{(byte)240,(byte)253,(byte)250}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorder(s);
        return s;
    }

    private void setBorder(XSSFCellStyle s) {
        s.setBorderTop(BorderStyle.THIN);    s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);   s.setBorderRight(BorderStyle.THIN);
        XSSFColor grey = new XSSFColor(new byte[]{(byte)209,(byte)213,(byte)219}, null);
        s.setTopBorderColor(grey);   s.setBottomBorderColor(grey);
        s.setLeftBorderColor(grey);  s.setRightBorderColor(grey);
    }
}