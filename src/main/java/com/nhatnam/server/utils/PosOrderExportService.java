// src/main/java/com/nhatnam/server/utils/PosOrderExportService.java
package com.nhatnam.server.utils;

import com.nhatnam.server.dto.PosOrderExportDto;
import com.nhatnam.server.repository.pos.PosOrderExportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DT_FMT    = DateTimeFormatter.ofPattern("HH:mm dd/MM/yy");

    // ── Public API ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] exportForStore(Long storeId, String storeName, Long fromMs, Long toMs) {
        List<PosOrderExportDto> rows = exportRepo.findForStore(storeId, fromMs, toMs);
        return buildExcel(rows, fromMs, toMs, storeName, false);
    }

    @Transactional(readOnly = true)
    public byte[] exportForSuperAdmin(Long fromMs, Long toMs) {
        List<PosOrderExportDto> rows = exportRepo.findForAllStores(fromMs, toMs);
        return buildExcel(rows, fromMs, toMs, null, true);
    }

    @Transactional(readOnly = true)
    public byte[] exportForSuperAdmin(Long storeId, String storeName, Long fromMs, Long toMs) {
        List<PosOrderExportDto> rows = exportRepo.findForStore(storeId, fromMs, toMs);
        return buildExcel(rows, fromMs, toMs, storeName, false);
    }

    // ── Core builder ──────────────────────────────────────────────

    private byte[] buildExcel(List<PosOrderExportDto> rows,
                              Long fromMs, Long toMs,
                              String storeName, boolean allStores) {
        SXSSFWorkbook wb = new SXSSFWorkbook(500);
        wb.setCompressTempFiles(true);

        try {
            SXSSFSheet sheet = wb.createSheet("Orders");
            sheet.setDefaultColumnWidth(18);

            int lastCol = allStores ? 19 : 18;
            int[] colWidths = allStores
                    ? new int[]{30,28,22,20,18,14,12,10,14,18,14,16,14,20,14,14,8,8,24,12}
                    : new int[]{28,22,20,18,14,12,10,14,18,14,16,14,20,14,14,8,8,24,12};
            for (int i = 0; i < colWidths.length; i++)
                sheet.setColumnWidth(i, colWidths[i] * 256);

            Styles st = new Styles(wb);
            List<CellRangeAddress> merges = new ArrayList<>();

            int rowNum = 0;

            // Title
            Row titleRow = sheet.createRow(rowNum++);
            titleRow.setHeightInPoints(30);
            Cell tc = titleRow.createCell(0);
            tc.setCellValue("BÁO CÁO ĐƠN HÀNG POS");
            tc.setCellStyle(st.title);
            merges.add(new CellRangeAddress(0, 0, 0, lastCol));

            // Subtitle
            Row dateRow = sheet.createRow(rowNum++);
            Cell dc = dateRow.createCell(0);
            dc.setCellValue("Khoảng thời gian: từ ngày " + fmtDate(fromMs) + " đến ngày " + fmtDate(toMs));
            dc.setCellStyle(st.subtitle);
            merges.add(new CellRangeAddress(1, 1, 0, lastCol));
            rowNum++; // blank row

            // Header
            String[] headers = allStores
                    ? new String[]{"Xe / Store","Ca làm việc","OrderID#",
                    "Tên KH","SĐT KH","Số tiền","Giảm giá","VAT","Tổng cuối",
                    "Thời gian","Nguồn","Thanh toán",
                    "Danh mục","Tên món","Giá gốc","Giá bán","% Giảm","SL","Nguyên liệu","Số lượng NL"}
                    : new String[]{"Ca làm việc","OrderID#",
                    "Tên KH","SĐT KH","Số tiền","Giảm giá","VAT","Tổng cuối",
                    "Thời gian","Nguồn","Thanh toán",
                    "Danh mục","Tên món","Giá gốc","Giá bán","% Giảm","SL","Nguyên liệu","Số lượng NL"};

            Row hRow = sheet.createRow(rowNum++);
            hRow.setHeightInPoints(22);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(st.header);
            }

            // Data
            rowNum = allStores
                    ? writeAllStores(sheet, rows, rowNum, st, merges)
                    : writeSingleStore(sheet, rows, rowNum, st, merges);

            for (CellRangeAddress region : merges) {
                sheet.addMergedRegionUnsafe(region);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            log.error("[EXPORT] Excel build error", e);
            throw new RuntimeException("Lỗi tạo file Excel: " + e.getMessage(), e);
        } finally {
            try { wb.dispose(); } catch (Exception ignore) {}
            try { wb.close(); } catch (Exception ignore) {}
        }
    }

    // ── writeAllStores ────────────────────────────────────────────

    private int writeAllStores(SXSSFSheet sheet, List<PosOrderExportDto> rows, int rowNum,
                               Styles st, List<CellRangeAddress> merges) {

        Map<Long, List<PosOrderExportDto>> byStore = rows.stream()
                .collect(Collectors.groupingBy(PosOrderExportDto::storeId,
                        LinkedHashMap::new, Collectors.toList()));

        for (var storeEntry : byStore.entrySet()) {
            int storeStartRow = rowNum;
            List<PosOrderExportDto> storeRows = storeEntry.getValue();
            PosOrderExportDto sf0 = storeRows.get(0);
            String storeLabel = buildStoreLabel(sf0.storeName(), sf0.storeAddress(), sf0.storePhone());

            Map<Long, List<PosOrderExportDto>> byShift = storeRows.stream()
                    .collect(Collectors.groupingBy(PosOrderExportDto::shiftId,
                            LinkedHashMap::new, Collectors.toList()));

            for (var shiftEntry : byShift.entrySet()) {
                List<PosOrderExportDto> shiftRows = shiftEntry.getValue();
                PosOrderExportDto shf = shiftRows.get(0);
                String shiftLabel = buildShiftLabel(shf.shiftId(), shf.shiftStaffName(),
                        shf.shiftOpenTime(), shf.shiftCloseTime());
                int shiftStartRow = rowNum;

                Map<Long, List<PosOrderExportDto>> byOrder = shiftRows.stream()
                        .collect(Collectors.groupingBy(PosOrderExportDto::orderId,
                                LinkedHashMap::new, Collectors.toList()));

                for (var orderEntry : byOrder.entrySet()) {
                    List<PosOrderExportDto> ingRows = orderEntry.getValue();
                    PosOrderExportDto of = ingRows.get(0);
                    int orderStartRow = rowNum;

                    List<List<PosOrderExportDto>> itemGroups = groupByItem(ingRows);
                    int itemIndex = 0;

                    for (List<PosOrderExportDto> itemIngRows : itemGroups) {
                        PosOrderExportDto itemFirst = itemIngRows.get(0);
                        int itemStartRow = rowNum;

                        // ADDON: nguyên liệu thường xếp TRƯỚC, addon xếp SAU.
                        // Dòng addon KHÔNG bị merge vào ô giá của món — mỗi addon
                        // hiển thị giá gốc / giá bán riêng của chính nó.
                        List<PosOrderExportDto> plainIngs = itemIngRows.stream()
                                .filter(PosOrderExportDto::hasIngredient)
                                .filter(x -> !x.isAddonIngredient())
                                .collect(Collectors.toList());
                        List<PosOrderExportDto> addonIngs = itemIngRows.stream()
                                .filter(PosOrderExportDto::hasIngredient)
                                .filter(PosOrderExportDto::isAddonIngredient)
                                .collect(Collectors.toList());

                        // số dòng dành cho món chính (phần được merge)
                        int mainRows = Math.max(1, plainIngs.size());
                        int rowsForItem = mainRows + addonIngs.size();

                        for (int ii = 0; ii < rowsForItem; ii++) {
                            Row row = sheet.createRow(rowNum++);
                            row.setHeightInPoints(16);

                            // Col 0: Store
                            Cell c0 = row.createCell(0);
                            c0.setCellStyle(st.store);
                            if (ii == 0 && itemIndex == 0) c0.setCellValue(storeLabel);

                            // Col 1: Shift
                            Cell c1 = row.createCell(1);
                            c1.setCellStyle(st.shift);
                            if (ii == 0 && itemIndex == 0) c1.setCellValue(shiftLabel);

                            // Col 2–11: Order
                            if (ii == 0 && itemIndex == 0) {
                                setL(row, 2,  of.orderCode(), st.data);
                                setL(row, 3,  nullDash(of.customerName()), st.data);
                                setL(row, 4,  nullDash(of.customerPhone()), st.data);
                                setN(row, 5,  of.totalAmount().doubleValue(), st.num);
                                setN(row, 6,  of.getDiscount(), st.num);
                                setN(row, 7,  of.getVat(), st.num);
                                setN(row, 8,  of.finalAmount().doubleValue(), st.num);
                                setL(row, 9,  fmtDateTime(of.createdAt()), st.data);
                                setL(row, 10, srcLabel(of.orderSource()), st.data);
                                setL(row, 11, pmLabel(of.paymentMethod()), st.data);
                            }

                            // Col 12–17: Item / Addon
                            if (ii >= mainRows) {
                                // Dòng ADDON — giá của chính addon, không merge
                                PosOrderExportDto ad = addonIngs.get(ii - mainRows);
                                double aGross = ad.ingredientAddonPrice() != null
                                        ? ad.ingredientAddonPrice().doubleValue() : 0;
                                double aNet = ad.addonNetOrGross() != null
                                        ? ad.addonNetOrGross().doubleValue() : 0;
                                setL(row, 12, "Addon", st.data);
                                setL(row, 13, "Addon: " + nvl(ad.ingredientName()), st.data);
                                setN(row, 14, aGross, st.num);
                                setN(row, 15, aNet, st.num);
                                setL(row, 16, "-", st.data);
                                setN(row, 17, ad.ingredientSelectedCount() != null
                                        ? ad.ingredientSelectedCount() : 0, st.num);
                            } else if (ii == 0 && itemFirst.hasItem()) {
                                double baseP = itemFirst.basePrice() != null ? itemFirst.basePrice().doubleValue() : 0;
                                double price = itemFirst.finalUnitPrice() != null ? itemFirst.finalUnitPrice().doubleValue() : 0;
                                double pct   = itemFirst.discountPercent() != null ? itemFirst.discountPercent().doubleValue() : 0;
                                setL(row, 12, nvl(itemFirst.categoryName()), st.data);
                                setL(row, 13, nvl(itemFirst.productName()), st.data);
                                setN(row, 14, baseP, st.num);
                                setN(row, 15, price, st.num);
                                setL(row, 16, (int) pct + "%", st.data);
                                setN(row, 17, itemFirst.quantity() != null ? itemFirst.quantity() : 0, st.num);
                            }

                            // Col 18–19: Ingredient
                            // FIX: dùng quantity_used trực tiếp (đã nhân qty trong DB), không nhân thêm
                            PosOrderExportDto ingRow = (ii >= mainRows)
                                    ? addonIngs.get(ii - mainRows)
                                    : (ii < plainIngs.size() ? plainIngs.get(ii) : null);
                            if (ingRow != null) {
                                setL(row, 18, nvl(ingRow.ingredientName()), st.data);
                                setN(row, 19, truncate3(ingRow.ingredientQty()), st.numDec);
                            }
                        }

                        // Chỉ merge ô giá món trên phần dòng của MÓN CHÍNH,
                        // các dòng addon phía dưới giữ giá riêng.
                        if (mainRows > 1)
                            for (int col = 12; col <= 17; col++)
                                merges.add(new CellRangeAddress(
                                        itemStartRow, itemStartRow + mainRows - 1, col, col));

                        itemIndex++;
                    }

                    if (rowNum - 1 > orderStartRow)
                        for (int col = 2; col <= 11; col++)
                            merges.add(new CellRangeAddress(orderStartRow, rowNum - 1, col, col));
                }

                if (rowNum - 1 > shiftStartRow)
                    merges.add(new CellRangeAddress(shiftStartRow, rowNum - 1, 1, 1));
            }

            if (rowNum - 1 > storeStartRow)
                merges.add(new CellRangeAddress(storeStartRow, rowNum - 1, 0, 0));
        }
        return rowNum;
    }

    // ── writeSingleStore ──────────────────────────────────────────

    private int writeSingleStore(SXSSFSheet sheet, List<PosOrderExportDto> rows, int rowNum,
                                 Styles st, List<CellRangeAddress> merges) {

        Map<Long, List<PosOrderExportDto>> byShift = rows.stream()
                .collect(Collectors.groupingBy(PosOrderExportDto::shiftId,
                        LinkedHashMap::new, Collectors.toList()));

        for (var shiftEntry : byShift.entrySet()) {
            List<PosOrderExportDto> shiftRows = shiftEntry.getValue();
            PosOrderExportDto shf = shiftRows.get(0);
            String shiftLabel = buildShiftLabel(shf.shiftId(), shf.shiftStaffName(),
                    shf.shiftOpenTime(), shf.shiftCloseTime());
            int shiftStartRow = rowNum;

            Map<Long, List<PosOrderExportDto>> byOrder = shiftRows.stream()
                    .collect(Collectors.groupingBy(PosOrderExportDto::orderId,
                            LinkedHashMap::new, Collectors.toList()));

            for (var orderEntry : byOrder.entrySet()) {
                List<PosOrderExportDto> ingRows = orderEntry.getValue();
                PosOrderExportDto of = ingRows.get(0);
                int orderStartRow = rowNum;

                List<List<PosOrderExportDto>> itemGroups = groupByItem(ingRows);
                int itemIndex = 0;

                for (List<PosOrderExportDto> itemIngRows : itemGroups) {
                    PosOrderExportDto itemFirst = itemIngRows.get(0);
                    int itemStartRow = rowNum;

                    // ADDON: nguyên liệu thường xếp TRƯỚC, addon xếp SAU.
                    // Dòng addon KHÔNG bị merge vào ô giá của món.
                    List<PosOrderExportDto> plainIngs = itemIngRows.stream()
                            .filter(PosOrderExportDto::hasIngredient)
                            .filter(x -> !x.isAddonIngredient())
                            .collect(Collectors.toList());
                    List<PosOrderExportDto> addonIngs = itemIngRows.stream()
                            .filter(PosOrderExportDto::hasIngredient)
                            .filter(PosOrderExportDto::isAddonIngredient)
                            .collect(Collectors.toList());

                    int mainRows = Math.max(1, plainIngs.size());
                    int rowsForItem = mainRows + addonIngs.size();

                    for (int ii = 0; ii < rowsForItem; ii++) {
                        Row row = sheet.createRow(rowNum++);
                        row.setHeightInPoints(16);

                        // Col 0: Shift
                        Cell c0 = row.createCell(0);
                        c0.setCellStyle(st.shift);
                        if (ii == 0 && itemIndex == 0) c0.setCellValue(shiftLabel);

                        // Col 1–10: Order
                        if (ii == 0 && itemIndex == 0) {
                            setL(row, 1,  of.orderCode(), st.data);
                            setL(row, 2,  nullDash(of.customerName()), st.data);
                            setL(row, 3,  nullDash(of.customerPhone()), st.data);
                            setN(row, 4,  of.totalAmount().doubleValue(), st.num);
                            setN(row, 5,  of.getDiscount(), st.num);
                            setN(row, 6,  of.getVat(), st.num);
                            setN(row, 7,  of.finalAmount().doubleValue(), st.num);
                            setL(row, 8,  fmtDateTime(of.createdAt()), st.data);
                            setL(row, 9,  srcLabel(of.orderSource()), st.data);
                            setL(row, 10, pmLabel(of.paymentMethod()), st.data);
                        }

                        // Col 11–16: Item / Addon
                        if (ii >= mainRows) {
                            // Dòng ADDON — giá của chính addon, không merge
                            PosOrderExportDto ad = addonIngs.get(ii - mainRows);
                            double aGross = ad.ingredientAddonPrice() != null
                                    ? ad.ingredientAddonPrice().doubleValue() : 0;
                            double aNet = ad.addonNetOrGross() != null
                                    ? ad.addonNetOrGross().doubleValue() : 0;
                            setL(row, 11, "Addon", st.data);
                            setL(row, 12, "Addon: " + nvl(ad.ingredientName()), st.data);
                            setN(row, 13, aGross, st.num);
                            setN(row, 14, aNet, st.num);
                            setL(row, 15, "-", st.data);
                            setN(row, 16, ad.ingredientSelectedCount() != null
                                    ? ad.ingredientSelectedCount() : 0, st.num);
                        } else if (ii == 0 && itemFirst.hasItem()) {
                            double baseP = itemFirst.basePrice() != null ? itemFirst.basePrice().doubleValue() : 0;
                            double price = itemFirst.finalUnitPrice() != null ? itemFirst.finalUnitPrice().doubleValue() : 0;
                            double pct   = itemFirst.discountPercent() != null ? itemFirst.discountPercent().doubleValue() : 0;
                            setL(row, 11, nvl(itemFirst.categoryName()), st.data);
                            setL(row, 12, nvl(itemFirst.productName()), st.data);
                            setN(row, 13, baseP, st.num);
                            setN(row, 14, price, st.num);
                            setL(row, 15, (int) pct + "%", st.data);
                            setN(row, 16, itemFirst.quantity() != null ? itemFirst.quantity() : 0, st.num);
                        }

                        // Col 17–18: Ingredient
                        // FIX 1: col 18 (trước đây bị ghi nhầm vào col 19)
                        // FIX 2: dùng quantity_used trực tiếp, không nhân productQty
                        PosOrderExportDto ingRow = (ii >= mainRows)
                                ? addonIngs.get(ii - mainRows)
                                : (ii < plainIngs.size() ? plainIngs.get(ii) : null);
                        if (ingRow != null) {
                            setL(row, 17, nvl(ingRow.ingredientName()), st.data);
                            setN(row, 18, truncate3(ingRow.ingredientQty()), st.numDec);
                        }
                    }

                    // Chỉ merge ô giá món trên phần dòng của MÓN CHÍNH
                    if (mainRows > 1)
                        for (int col = 11; col <= 16; col++)
                            merges.add(new CellRangeAddress(
                                    itemStartRow, itemStartRow + mainRows - 1, col, col));

                    itemIndex++;
                }

                if (rowNum - 1 > orderStartRow)
                    for (int col = 1; col <= 10; col++)
                        merges.add(new CellRangeAddress(orderStartRow, rowNum - 1, col, col));
            }

            if (rowNum - 1 > shiftStartRow)
                merges.add(new CellRangeAddress(shiftStartRow, rowNum - 1, 0, 0));
        }
        return rowNum;
    }

    // ── Styles ────────────────────────────────────────────────────

    private static class Styles {
        final CellStyle title, subtitle, header, store, shift, data, num, numDec;

        private static final byte[] WHITE   = {(byte)255,(byte)255,(byte)255};
        private static final byte[] BG_DATA = {(byte)239,(byte)246,(byte)255};
        private static final byte[] GREY    = {(byte)209,(byte)213,(byte)219};

        Styles(SXSSFWorkbook wb) {
            title   = mkTitle(wb);
            subtitle= mkSubtitle(wb);
            header  = mkHeader(wb);
            store   = mkStore(wb);
            shift   = mkShift(wb);
            data    = mkData(wb);
            num     = mkNum(wb, "#,##0");
            // FIX: ### thay vì ## → hiển thị tối đa 3 chữ số, bỏ trailing zero
            // 1.000 → "1"  |  1.500 → "1.5"  |  0.896 → "0.896"
            numDec  = mkNum(wb, "#,##0.###");
        }

        private static XSSFCellStyle newStyle(SXSSFWorkbook wb) {
            return (XSSFCellStyle) wb.createCellStyle();
        }

        private static XSSFFont newFont(SXSSFWorkbook wb, boolean bold, short size, byte[] color) {
            XSSFFont f = (XSSFFont) wb.createFont();
            f.setBold(bold);
            f.setFontHeightInPoints(size);
            if (color != null) f.setColor(new XSSFColor(color, null));
            return f;
        }

        private static void bg(XSSFCellStyle s, byte[] rgb) {
            s.setFillForegroundColor(new XSSFColor(rgb, null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        private static void border(XSSFCellStyle s) {
            XSSFColor grey = new XSSFColor(GREY, null);
            s.setBorderTop(BorderStyle.THIN);    s.setTopBorderColor(grey);
            s.setBorderBottom(BorderStyle.THIN); s.setBottomBorderColor(grey);
            s.setBorderLeft(BorderStyle.THIN);   s.setLeftBorderColor(grey);
            s.setBorderRight(BorderStyle.THIN);  s.setRightBorderColor(grey);
        }

        private static CellStyle mkTitle(SXSSFWorkbook wb) {
            XSSFCellStyle s = newStyle(wb);
            s.setFont(newFont(wb, true, (short)16, WHITE));
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            bg(s, new byte[]{(byte)30,(byte)64,(byte)175});
            return s;
        }

        private static CellStyle mkSubtitle(SXSSFWorkbook wb) {
            XSSFCellStyle s = newStyle(wb);
            XSSFFont f = newFont(wb, false, (short)10, null);
            f.setItalic(true);
            s.setFont(f);
            s.setAlignment(HorizontalAlignment.LEFT);
            return s;
        }

        private static CellStyle mkHeader(SXSSFWorkbook wb) {
            XSSFCellStyle s = newStyle(wb);
            s.setFont(newFont(wb, true, (short)11, WHITE));
            s.setAlignment(HorizontalAlignment.LEFT);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            bg(s, new byte[]{(byte)37,(byte)99,(byte)235});
            s.setWrapText(true);
            border(s);
            return s;
        }

        private static CellStyle mkStore(SXSSFWorkbook wb) {
            XSSFCellStyle s = newStyle(wb);
            s.setFont(newFont(wb, true, (short)10, WHITE));
            s.setAlignment(HorizontalAlignment.LEFT);
            s.setVerticalAlignment(VerticalAlignment.TOP);
            bg(s, new byte[]{(byte)15,(byte)23,(byte)100});
            s.setWrapText(true);
            border(s);
            return s;
        }

        private static CellStyle mkShift(SXSSFWorkbook wb) {
            XSSFCellStyle s = newStyle(wb);
            s.setFont(newFont(wb, true, (short)10, WHITE));
            s.setAlignment(HorizontalAlignment.LEFT);
            s.setVerticalAlignment(VerticalAlignment.TOP);
            bg(s, new byte[]{(byte)30,(byte)64,(byte)175});
            s.setWrapText(true);
            border(s);
            return s;
        }

        private static CellStyle mkData(SXSSFWorkbook wb) {
            XSSFCellStyle s = newStyle(wb);
            s.setAlignment(HorizontalAlignment.LEFT);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            bg(s, BG_DATA);
            border(s);
            return s;
        }

        private static CellStyle mkNum(SXSSFWorkbook wb, String fmt) {
            XSSFCellStyle s = newStyle(wb);
            s.setDataFormat(wb.createDataFormat().getFormat(fmt));
            s.setAlignment(HorizontalAlignment.RIGHT);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            bg(s, BG_DATA);
            border(s);
            return s;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Cắt xuống 3 chữ số sau dấu phẩy — KHÔNG làm tròn (dùng FLOOR).
     * Kết hợp với Excel format "#,##0.###" sẽ bỏ trailing zeros tự động:
     *   1.0000  → 1.000 → Excel hiển thị "1"
     *   1.5000  → 1.500 → Excel hiển thị "1.5"
     *   0.8960  → 0.896 → Excel hiển thị "0.896"
     *   1.5353  → 1.535 → Excel hiển thị "1.535"
     */
    private static double truncate3(BigDecimal bd) {
        if (bd == null) return 0;
        return bd.setScale(3, RoundingMode.FLOOR).doubleValue();
    }

    private List<List<PosOrderExportDto>> groupByItem(List<PosOrderExportDto> rows) {
        List<List<PosOrderExportDto>> groups = new ArrayList<>();
        List<PosOrderExportDto> current = new ArrayList<>();
        Long lastItemId = null;
        for (PosOrderExportDto r : rows) {
            Long itemId = r.orderItemId();
            if (!Objects.equals(itemId, lastItemId) && lastItemId != null) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(r);
            lastItemId = itemId;
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
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
            case "CASH"                     -> "Tiền mặt";
            case "BANK_TRANSFER","TRANSFER" -> "Chuyển khoản";
            case "MOMO"                     -> "MoMo";
            case "VNPAY"                    -> "VNPay";
            case "ZALOPAY"                  -> "ZaloPay";
            default                         -> m;
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
}