// src/main/java/com/nhatnam/server/utils/PosOrderExportService.java
package com.nhatnam.server.utils;

import com.nhatnam.server.dto.PosOrderExportDto;
import com.nhatnam.server.repository.pos.PosOrderExportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class PosOrderExportService {

    private final PosOrderExportRepository exportRepo;

    private static final ZoneId VN_ZONE  = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yy");

    @Transactional(readOnly = true)
    public byte[] exportForStore(Long storeId, String storeName,
                                 Long fromMs, Long toMs) {
        List<PosOrderExportDto> rows = exportRepo.findForStore(storeId, fromMs, toMs);
        return buildExcel(rows, fromMs, toMs, storeName, false);
    }

    @Transactional(readOnly = true)
    public byte[] exportForSuperAdmin(Long fromMs, Long toMs) {
        List<PosOrderExportDto> rows = exportRepo.findForAllStores(fromMs, toMs);
        return buildExcel(rows, fromMs, toMs, null, true);
    }

    @Transactional(readOnly = true)
    public byte[] exportForSuperAdmin(Long storeId, String storeName,
                                      Long fromMs, Long toMs) {
        List<PosOrderExportDto> rows = exportRepo.findForStore(storeId, fromMs, toMs);
        return buildExcel(rows, fromMs, toMs, storeName, false);
    }

    private byte[] buildExcel(List<PosOrderExportDto> rows,
                              Long fromMs, Long toMs,
                              String storeName, boolean allStores) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Orders");
            sheet.setDefaultColumnWidth(18);

            CellStyle headerStyle = makeHeaderStyle(wb);
            CellStyle storeStyle  = makeStoreStyle(wb);
            CellStyle shiftStyle  = makeShiftStyle(wb);
            CellStyle dataStyle   = makeDataStyle(wb);
            CellStyle numStyle    = makeNumStyle(wb);

            // allStores = 18 cols (0–17), single = 17 cols (0–16)
            int lastCol = allStores ? 17 : 16;

            int[] minW = allStores
                    ? new int[]{30,28,22,20,18,14,12,10,14,18,14,16,14,20,14,14,8,8}
                    : new int[]{28,22,20,18,14,12,10,14,18,14,16,14,20,14,14,8,8};
            for (int i = 0; i < minW.length; i++)
                sheet.setColumnWidth(i, minW[i] * 256);

            int rowNum = 0;

            // Title
            Row titleRow = sheet.createRow(rowNum++);
            titleRow.setHeightInPoints(30);
            Cell tc = titleRow.createCell(0);
            tc.setCellValue("BÁO CÁO ĐƠN HÀNG POS");
            tc.setCellStyle(makeTitleStyle(wb));
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastCol));

            // Date range
            Row dateRow = sheet.createRow(rowNum++);
            Cell dc = dateRow.createCell(0);
            dc.setCellValue("Khoảng thời gian: từ ngày "
                    + fmtDate(fromMs) + " đến ngày " + fmtDate(toMs));
            dc.setCellStyle(makeSubtitleStyle(wb));
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, lastCol));
            rowNum++;

            // Headers
            String[] headers = allStores
                    ? new String[]{"Xe / Store","Ca làm việc","OrderID#",
                    "Tên KH","SĐT KH","Số tiền","Giảm giá","VAT","Tổng cuối",
                    "Thời gian","Nguồn","Thanh toán",
                    "Danh mục","Tên món","Giá gốc","Giá bán","% Giảm","SL"}
                    : new String[]{"Ca làm việc","OrderID#",
                    "Tên KH","SĐT KH","Số tiền","Giảm giá","VAT","Tổng cuối",
                    "Thời gian","Nguồn","Thanh toán",
                    "Danh mục","Tên món","Giá gốc","Giá bán","% Giảm","SL"};

            Row hRow = sheet.createRow(rowNum++);
            hRow.setHeightInPoints(22);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            rowNum = allStores
                    ? writeAllStores(sheet, rows, rowNum, storeStyle, shiftStyle, dataStyle, numStyle)
                    : writeSingleStore(sheet, rows, rowNum, shiftStyle, dataStyle, numStyle);

            final int PADDING = 4 * 256;
            for (int col = 0; col <= lastCol; col++) {
                sheet.autoSizeColumn(col, true);
                sheet.setColumnWidth(col,
                        Math.max(sheet.getColumnWidth(col) + PADDING, minW[col] * 256));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            log.error("Excel export error", e);
            throw new RuntimeException("Lỗi tạo file Excel: " + e.getMessage(), e);
        }
    }

    private int writeAllStores(XSSFSheet sheet, List<PosOrderExportDto> rows, int rowNum,
                               CellStyle storeStyle, CellStyle shiftStyle,
                               CellStyle dataStyle, CellStyle numStyle) {

        Map<Long, List<PosOrderExportDto>> byStore = rows.stream()
                .collect(Collectors.groupingBy(PosOrderExportDto::storeId,
                        LinkedHashMap::new, Collectors.toList()));

        for (var storeEntry : byStore.entrySet()) {
            int storeStartRow = rowNum;
            List<PosOrderExportDto> storeRows = storeEntry.getValue();
            PosOrderExportDto first = storeRows.get(0);
            String storeLabel = buildStoreLabel(
                    first.storeName(), first.storeAddress(), first.storePhone());

            Map<Long, List<PosOrderExportDto>> byShift = storeRows.stream()
                    .collect(Collectors.groupingBy(PosOrderExportDto::shiftId,
                            LinkedHashMap::new, Collectors.toList()));

            for (var shiftEntry : byShift.entrySet()) {
                List<PosOrderExportDto> shiftRows = shiftEntry.getValue();
                PosOrderExportDto sf = shiftRows.get(0);
                String shiftLabel = buildShiftLabel(
                        sf.shiftId(), sf.shiftStaffName(),
                        sf.shiftOpenTime(), sf.shiftCloseTime());
                int shiftStartRow = rowNum;

                Map<Long, List<PosOrderExportDto>> byOrder = shiftRows.stream()
                        .collect(Collectors.groupingBy(PosOrderExportDto::orderId,
                                LinkedHashMap::new, Collectors.toList()));

                for (var orderEntry : byOrder.entrySet()) {
                    List<PosOrderExportDto> orderRows = orderEntry.getValue();
                    PosOrderExportDto of = orderRows.get(0);
                    int orderStartRow = rowNum;
                    int itemCount = orderRows.size();

                    for (int ii = 0; ii < itemCount; ii++) {
                        PosOrderExportDto r = orderRows.get(ii);
                        Row row = sheet.createRow(rowNum++);
                        row.setHeightInPoints(16);

                        Cell c0 = row.createCell(0);
                        c0.setCellValue(storeLabel);
                        c0.setCellStyle(storeStyle);

                        Cell c1 = row.createCell(1);
                        c1.setCellValue(shiftLabel);
                        c1.setCellStyle(shiftStyle);

                        if (ii == 0) {
                            setL(row, 2,  of.orderCode(), dataStyle);
                            setL(row, 3,  nullDash(of.customerName()), dataStyle);
                            setL(row, 4,  nullDash(of.customerPhone()), dataStyle);
                            setN(row, 5,  of.totalAmount().doubleValue(), numStyle);
                            setN(row, 6,  of.getDiscount(), numStyle);
                            setN(row, 7,  of.getVat(), numStyle);
                            setN(row, 8,  of.finalAmount().doubleValue(), numStyle);
                            setL(row, 9,  fmtDateTime(of.createdAt()), dataStyle);
                            setL(row, 10, srcLabel(of.orderSource()), dataStyle);
                            setL(row, 11, pmLabel(of.paymentMethod()), dataStyle);
                        } else {
                            for (int c = 2; c <= 11; c++)
                                row.createCell(c).setCellStyle(dataStyle);
                        }

                        // Col 12-17: item info (Danh mục, Tên món, Giá gốc, Giá bán, % Giảm, SL)
                        if (r.hasItem()) {
                            double baseP = r.basePrice() != null ? r.basePrice().doubleValue() : 0;
                            double price = r.finalUnitPrice() != null ? r.finalUnitPrice().doubleValue() : 0;
                            double pct   = r.discountPercent() != null ? r.discountPercent().doubleValue() : 0;
                            setL(row, 12, nvl(r.categoryName()), dataStyle);
                            setL(row, 13, nvl(r.productName()), dataStyle);
                            setN(row, 14, baseP, numStyle);                          // Giá gốc
                            setN(row, 15, price, numStyle);                          // Giá bán
                            setL(row, 16, (int) pct + "%", dataStyle);               // % Giảm
                            setN(row, 17, r.quantity() != null ? r.quantity() : 0, numStyle); // SL
                        } else {
                            for (int c = 12; c <= 17; c++)
                                row.createCell(c).setCellStyle(dataStyle);
                        }
                    }

                    if (itemCount > 1)
                        for (int col = 2; col <= 11; col++)
                            sheet.addMergedRegion(new CellRangeAddress(
                                    orderStartRow, rowNum - 1, col, col));
                }

                if (rowNum - 1 > shiftStartRow)
                    sheet.addMergedRegion(new CellRangeAddress(
                            shiftStartRow, rowNum - 1, 1, 1));
            }

            if (rowNum - 1 > storeStartRow)
                sheet.addMergedRegion(new CellRangeAddress(
                        storeStartRow, rowNum - 1, 0, 0));
        }
        return rowNum;
    }

    private int writeSingleStore(XSSFSheet sheet, List<PosOrderExportDto> rows, int rowNum,
                                 CellStyle shiftStyle, CellStyle dataStyle, CellStyle numStyle) {

        Map<Long, List<PosOrderExportDto>> byShift = rows.stream()
                .collect(Collectors.groupingBy(PosOrderExportDto::shiftId,
                        LinkedHashMap::new, Collectors.toList()));

        for (var shiftEntry : byShift.entrySet()) {
            List<PosOrderExportDto> shiftRows = shiftEntry.getValue();
            PosOrderExportDto sf = shiftRows.get(0);
            String shiftLabel = buildShiftLabel(
                    sf.shiftId(), sf.shiftStaffName(),
                    sf.shiftOpenTime(), sf.shiftCloseTime());
            int shiftStartRow = rowNum;

            Map<Long, List<PosOrderExportDto>> byOrder = shiftRows.stream()
                    .collect(Collectors.groupingBy(PosOrderExportDto::orderId,
                            LinkedHashMap::new, Collectors.toList()));

            for (var orderEntry : byOrder.entrySet()) {
                List<PosOrderExportDto> orderRows = orderEntry.getValue();
                PosOrderExportDto of = orderRows.get(0);
                int orderStartRow = rowNum;
                int itemCount = orderRows.size();

                for (int ii = 0; ii < itemCount; ii++) {
                    PosOrderExportDto r = orderRows.get(ii);
                    Row row = sheet.createRow(rowNum++);
                    row.setHeightInPoints(16);

                    Cell c0 = row.createCell(0);
                    c0.setCellValue(shiftLabel);
                    c0.setCellStyle(shiftStyle);

                    if (ii == 0) {
                        setL(row, 1,  of.orderCode(), dataStyle);
                        setL(row, 2,  nullDash(of.customerName()), dataStyle);
                        setL(row, 3,  nullDash(of.customerPhone()), dataStyle);
                        setN(row, 4,  of.totalAmount().doubleValue(), numStyle);
                        setN(row, 5,  of.getDiscount(), numStyle);
                        setN(row, 6,  of.getVat(), numStyle);
                        setN(row, 7,  of.finalAmount().doubleValue(), numStyle);
                        setL(row, 8,  fmtDateTime(of.createdAt()), dataStyle);
                        setL(row, 9,  srcLabel(of.orderSource()), dataStyle);
                        setL(row, 10, pmLabel(of.paymentMethod()), dataStyle);
                    } else {
                        for (int c = 1; c <= 10; c++)
                            row.createCell(c).setCellStyle(dataStyle);
                    }

                    // Col 11-16: item info (Danh mục, Tên món, Giá gốc, Giá bán, % Giảm, SL)
                    if (r.hasItem()) {
                        double baseP = r.basePrice() != null ? r.basePrice().doubleValue() : 0;
                        double price = r.finalUnitPrice() != null ? r.finalUnitPrice().doubleValue() : 0;
                        double pct   = r.discountPercent() != null ? r.discountPercent().doubleValue() : 0;
                        setL(row, 11, nvl(r.categoryName()), dataStyle);
                        setL(row, 12, nvl(r.productName()), dataStyle);
                        setN(row, 13, baseP, numStyle);                          // Giá gốc
                        setN(row, 14, price, numStyle);                          // Giá bán
                        setL(row, 15, (int) pct + "%", dataStyle);               // % Giảm
                        setN(row, 16, r.quantity() != null ? r.quantity() : 0, numStyle); // SL
                    } else {
                        for (int c = 11; c <= 16; c++)
                            row.createCell(c).setCellStyle(dataStyle);
                    }
                }

                if (itemCount > 1)
                    for (int col = 1; col <= 10; col++)
                        sheet.addMergedRegion(new CellRangeAddress(
                                orderStartRow, rowNum - 1, col, col));
            }

            if (rowNum - 1 > shiftStartRow)
                sheet.addMergedRegion(new CellRangeAddress(
                        shiftStartRow, rowNum - 1, 0, 0));
        }
        return rowNum;
    }

    private String buildStoreLabel(String name, String address, String phone) {
        StringBuilder sb = new StringBuilder(nvl(name));
        if (address != null && !address.isBlank()) sb.append("\n").append(address);
        if (phone   != null && !phone.isBlank())   sb.append("\n").append(phone);
        return sb.toString();
    }

    private String buildShiftLabel(Long id, String staffName, Long openTime, Long closeTime) {
        String open  = openTime  != null ? fmtDateTime(openTime)  : "?";
        String close = closeTime != null ? fmtDateTime(closeTime) : "Đang mở";
        return "SHIFT#" + id + " - " + nvl(staffName) + "\n" + open + " – " + close;
    }

    private String fmtDateTime(Long ms) {
        if (ms == null) return "";
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), VN_ZONE).format(DT_FMT);
    }

    private String fmtDate(Long ms) {
        if (ms == null) return "";
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), VN_ZONE).format(DATE_ONLY);
    }

    private String srcLabel(String s) {
        if (s == null) return "Take Away";
        return switch (s) {
            case "SHOPEE_FOOD" -> "ShopeeFood";
            case "GRAB_FOOD"   -> "GrabFood";
            case "DINE_IN"     -> "Dine In";
            default            -> "Take Away";
        };
    }

    private String pmLabel(String m) {
        if (m == null) return "Tiền mặt";
        return switch (m) {
            case "CASH"                    -> "Tiền mặt";
            case "BANK_TRANSFER","TRANSFER" -> "Chuyển khoản";
            case "MOMO"                    -> "MoMo";
            case "VNPAY"                   -> "VNPay";
            case "ZALOPAY"                 -> "ZaloPay";
            default                        -> m;
        };
    }

    private String nullDash(String s) { return (s != null && !s.isBlank()) ? s : "-"; }
    private String nvl(String s)      { return s != null ? s : ""; }

    private void setL(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        c.setCellStyle(style);
    }

    private void setN(Row row, int col, double value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private void setN(Row row, int col, int value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private CellStyle makeTitleStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setBold(true); f.setFontHeightInPoints((short) 16);
        f.setColor(new XSSFColor(new byte[]{(byte)255,(byte)255,(byte)255}, null));
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(new XSSFColor(new byte[]{(byte)30,(byte)64,(byte)175}, null));
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
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(new XSSFColor(new byte[]{(byte)37,(byte)99,(byte)235}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorder(s); s.setWrapText(true);
        return s;
    }

    private CellStyle makeStoreStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setBold(true); f.setFontHeightInPoints((short) 10);
        f.setColor(new XSSFColor(new byte[]{(byte)255,(byte)255,(byte)255}, null));
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.TOP);
        s.setFillForegroundColor(new XSSFColor(new byte[]{(byte)15,(byte)23,(byte)100}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorder(s); s.setWrapText(true);
        return s;
    }

    private CellStyle makeShiftStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setBold(true); f.setFontHeightInPoints((short) 10);
        f.setColor(new XSSFColor(new byte[]{(byte)255,(byte)255,(byte)255}, null));
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.TOP);
        s.setFillForegroundColor(new XSSFColor(new byte[]{(byte)30,(byte)64,(byte)175}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorder(s); s.setWrapText(true);
        return s;
    }

    private CellStyle makeDataStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(new XSSFColor(new byte[]{(byte)239,(byte)246,(byte)255}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorder(s); s.setWrapText(false);
        return s;
    }

    private CellStyle makeNumStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("#,##0"));
        s.setAlignment(HorizontalAlignment.RIGHT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setFillForegroundColor(new XSSFColor(new byte[]{(byte)239,(byte)246,(byte)255}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorder(s);
        return s;
    }

    private void setBorder(XSSFCellStyle s) {
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        XSSFColor grey = new XSSFColor(new byte[]{(byte)209,(byte)213,(byte)219}, null);
        s.setTopBorderColor(grey);
        s.setBottomBorderColor(grey);
        s.setLeftBorderColor(grey);
        s.setRightBorderColor(grey);
    }
}