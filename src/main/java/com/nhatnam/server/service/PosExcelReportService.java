package com.nhatnam.server.service;

import com.nhatnam.server.entity.pos.*;
import com.nhatnam.server.enumtype.*;
import com.nhatnam.server.repository.pos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
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
public class PosExcelReportService {

    private final PosShiftRepository shiftRepo;
    private final PosOrderRepository orderRepo;
    private final PosOrderItemRepository orderItemRepo;
    private final PosOrderItemIngredientRepository orderItemIngredientRepo;
    private final PosShiftOpenInventoryRepository openInvRepo;
    private final PosShiftCloseInventoryRepository closeInvRepo;
    private final PosShiftDenominationRepository openDenomRepo;
    private final PosShiftCloseDenominationRepository closeDenomRepo;
    private final PosIngredientRepository ingredientRepo;
    private final PosProductRepository productRepo;
    private final PosShiftStockImportRepository importRepo;
    private final PosUserStoreRepository posUserStoreRepo;

    // ════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════

    public byte[] generateShiftReport(Long shiftId) throws Exception {
        PosShift shift = shiftRepo.findById(shiftId)
                .orElseThrow(() -> new RuntimeException("Shift not found: " + shiftId));

        Long storeId = shift.getStoreId();
        String storeName = shift.getStoreName();

        return buildWorkbook(List.of(shift), storeName, storeId);
    }

    public byte[] generateRangeReport(String fromDate, String toDate, Long userId) throws Exception {
        // ============ LẤY STORE_ID TỪ USER (EAGER LOAD) ============
        PosUserStore userStore = posUserStoreRepo.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException(
                        "User ID " + userId + " chưa được gán vào store nào"));

        // ============ EAGER LOAD STORE INFO NGAY LẬP TỨC ============
        Long storeId = userStore.getStore().getId();
        String storeName = userStore.getStore().getName(); // Force load ngay
        // ===========================================================

        // ============ FILTER SHIFTS THEO STORE ============
        List<PosShift> shifts = shiftRepo.findShiftsByDateRangeAndUserStore(
                        fromDate, toDate, ShiftStatus.CLOSED, userId)
                .stream()
                .filter(s -> s.getStoreId().equals(storeId))
                .collect(Collectors.toList());
        // ==================================================

        if (shifts.isEmpty()) {
            throw new RuntimeException(
                    "Không có ca đã đóng trong khoảng thời gian này tại store " + storeName);
        }

        return buildWorkbook(shifts, storeName, storeId);
    }


    public String getStoreNameByUserId(Long userId) {
        return posUserStoreRepo.findByUserId(userId)
                .map(pus -> pus.getStore().getName())
                .orElseThrow(() -> new RuntimeException(
                        "User ID " + userId + " chưa được gán vào store nào"));
    }

    // ════════════════════════════════════════
    // WORKBOOK BUILDER
    // ════════════════════════════════════════

    private byte[] buildWorkbook(List<PosShift> shifts, String storeName, Long storeId) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSSFSheet sheetNL = wb.createSheet("Nguyên Liệu");
            XSSFSheet sheetDT = wb.createSheet("Doanh Thu");

            sheetNL.createFreezePane(0, 8);
            sheetDT.createFreezePane(0, 8);

            int rowNL = 0;
            int rowDT = 0;

            for (int i = 0; i < shifts.size(); i++) {
                PosShift shift = shifts.get(i);

                // ============ TRUYỀN STORE_ID VÀO ============
                rowNL = buildSheetNguyenLieu(wb, sheetNL, shift, rowNL, storeName, storeId);
                if (i < shifts.size() - 1) rowNL += 2;

                rowDT = buildSheetDoanhThu(wb, sheetDT, shift, rowDT, storeName, storeId);
                if (i < shifts.size() - 1) rowDT += 2;
                // ============================================
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    // ════════════════════════════════════════
    // SHEET NGUYÊN LIỆU
    // ════════════════════════════════════════

    private int buildSheetNguyenLieu(XSSFWorkbook wb, XSSFSheet ws, PosShift shift,
                                     int rowStart, String storeName, Long storeId) {

        int[] widths = {10, 25, 7, 10, 12, 12, 12, 12, 12, 9, 7, 7, 7, 7, 12, 6};
        for (int i = 0; i < widths.length; i++)
            ws.setColumnWidth(i, widths[i] * 256);

        List<PosShiftOpenInventory> openInv = openInvRepo.findByShift(shift);
        List<PosShiftCloseInventory> closeInv = closeInvRepo.findByShift(shift);
        List<PosShiftStockImport> importInv = importRepo.findByShiftWithIngredient(shift);

        Map<Long, Integer> importMap = new HashMap<>();
        for (PosShiftStockImport imp : importInv) {
            Long ingId = imp.getIngredient().getId();
            int packQty = imp.getPackQty() != null ? imp.getPackQty() : 0;
            int unitPerPack = imp.getIngredient().getUnitPerPack() != null ?
                    imp.getIngredient().getUnitPerPack() : 1;
            importMap.merge(ingId, packQty * unitPerPack, Integer::sum);
        }

        List<PosOrder> orders = orderRepo.findByShiftOrderByCreatedAtDesc(shift).stream()
                .filter(o -> o.getStatus() != PosOrderStatus.CANCELLED)
                .collect(Collectors.toList());

        Map<Long, BigDecimal[]> salesMap = new HashMap<>();
        for (PosOrder order : orders) {
            for (PosOrderItem item : orderItemRepo.findByOrder(order)) {
                boolean isApp = order.getOrderSource() == OrderSource.SHOPEE_FOOD ||
                        order.getOrderSource() == OrderSource.GRAB_FOOD;
                boolean isLanh = !isApp && item.getDiscountPercent() == 0 &&
                        isProductSinglePrice(item.getProductId());

                for (PosOrderItemIngredient si : orderItemIngredientRepo.findByOrderItem(item)) {
                    long ingId = si.getIngredientId();
                    salesMap.putIfAbsent(ingId, new BigDecimal[]{
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                    BigDecimal[] arr = salesMap.get(ingId);
                    BigDecimal qty = si.getQuantityUsed() != null ?
                            si.getQuantityUsed() : BigDecimal.ZERO;

                    if (order.getOrderSource() == OrderSource.SHOPEE_FOOD)
                        arr[4] = arr[4].add(qty);
                    else if (order.getOrderSource() == OrderSource.GRAB_FOOD)
                        arr[5] = arr[5].add(qty);
                    else if (isLanh)
                        arr[6] = arr[6].add(qty);
                    else if (item.getDiscountPercent() == 0)
                        arr[0] = arr[0].add(qty);
                    else if (item.getDiscountPercent() == 10)
                        arr[1] = arr[1].add(qty);
                    else if (item.getDiscountPercent() == 20)
                        arr[2] = arr[2].add(qty);
                    else if (item.getDiscountPercent() == 100)
                        arr[3] = arr[3].add(qty);
                    else
                        arr[0] = arr[0].add(qty);
                }
            }
        }

        Map<Long, PosShiftOpenInventory> openMap = openInv.stream()
                .collect(Collectors.toMap(i -> i.getIngredient().getId(), i -> i));
        Map<Long, PosShiftCloseInventory> closeMap = closeInv.stream()
                .collect(Collectors.toMap(i -> i.getIngredient().getId(), i -> i));

        // ============ FILTER INGREDIENTS THEO STORE_ID ============
        List<PosIngredient> allIngs = ingredientRepo
                .findByStoreIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(storeId);

        List<PosIngredient> mainIngs = allIngs.stream()
                .filter(i -> !IngredientType.SUB.equals(i.getIngredientType()))
                .toList();

        List<PosIngredient> subIngs = allIngs.stream()
                .filter(i -> IngredientType.SUB.equals(i.getIngredientType()))
                .toList();
        // ==========================================================

        ShiftMoney money = calcMoney(shift, orders);

        writeHeaderBlock(wb, ws, shift, money, 16, rowStart, storeName);
        writeSheet1Headers(wb, ws, rowStart + 6);

        int ROW = rowStart + 8;
        int stt = 1;

        XSSFCellStyle mainStyle = createCellStyle(wb, "E3F2FD", false, 10, "000000", "center");
        setBorder(mainStyle);
        XSSFCellStyle subStyle = createCellStyle(wb, "FFF3E0", false, 10, "000000", "center");
        setBorder(subStyle);
        XSSFCellStyle mergeEmptyStyle = createCellStyle(wb, "FFF3E0", false, 10, "000000", "center");
        setBorder(mergeEmptyStyle);

        // ── MAIN ingredients ────────────────────────────────────────────────────
        for (PosIngredient ing : mainIngs) {
            Long id = ing.getId();
            PosShiftOpenInventory oi = openMap.get(id);
            PosShiftCloseInventory ci = closeMap.get(id);
            BigDecimal[] sales = salesMap.getOrDefault(id, new BigDecimal[]{
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});

            int openPack = oi != null ? nvlInt(oi.getPackQuantity()) : 0;
            BigDecimal openUnit = oi != null ? nvlBD(oi.getUnitQuantity()) : BigDecimal.ZERO;
            int closePack = ci != null ? nvlInt(ci.getPackQuantity()) : 0;
            BigDecimal closeUnit = ci != null ? nvlBD(ci.getUnitQuantity()) : BigDecimal.ZERO;

            int upp = nvlInt(ing.getUnitPerPack(), 1);
            int impUnits = importMap.getOrDefault(id, 0);

            BigDecimal openingUnits = BigDecimal.valueOf((long) openPack * upp).add(openUnit);
            BigDecimal totalOpeningUnits = openingUnits.add(BigDecimal.valueOf(impUnits));
            BigDecimal closingUnits = BigDecimal.valueOf((long) closePack * upp).add(closeUnit);

            BigDecimal totalSold = sales[0].add(sales[1]).add(sales[2]).add(sales[3])
                    .add(sales[4]).add(sales[5]).add(sales[6]);

            BigDecimal expectedClosing = totalOpeningUnits.subtract(totalSold);
            BigDecimal diffBD = closingUnits.subtract(expectedClosing)
                    .setScale(2, RoundingMode.HALF_UP);

            int diffSign = diffBD.compareTo(BigDecimal.ZERO);
            String status = (diffSign == 0) ? "Đủ hàng" : (diffSign > 0 ? "Dư" : "Thiếu");
            String slVal = (diffSign == 0) ? "-" : fmtDecimal(diffBD.abs());

            int impPacks = (upp > 0 && impUnits > 0) ? impUnits / upp : 0;

            Row row = ws.createRow(ROW++);
            row.setHeightInPoints(18);
            writeDataCells(wb, row, (stt % 2 == 0), stt, ing.getName(),
                    openPack, openUnit,
                    sales[0], sales[1], sales[2], sales[3], sales[4], sales[5], sales[6],
                    impPacks, closePack, closeUnit,
                    status, slVal, diffSign);
            applyRowStyle(row, mainStyle, 16);
            stt++;
        }

        // ── SUB ingredients ─────────────────────────────────────────────────────
        for (PosIngredient ing : subIngs) {
            Long id = ing.getId();
            BigDecimal[] sales = salesMap.getOrDefault(id, new BigDecimal[]{
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});

            Row row = ws.createRow(ROW++);
            row.setHeightInPoints(18);

            Cell sttCell = row.createCell(0);
            sttCell.setCellValue(stt);
            sttCell.setCellStyle(subStyle);

            Cell nameCell = row.createCell(1);
            nameCell.setCellValue(ing.getName());
            nameCell.setCellStyle(subStyle);

            mergeCells(ws, ROW - 1, 2, ROW - 1, 3, "", mergeEmptyStyle);

            Cell s0 = row.createCell(4);
            s0.setCellValue(sales[0].doubleValue());
            s0.setCellStyle(subStyle);

            Cell s10 = row.createCell(5);
            s10.setCellValue(sales[1].doubleValue());
            s10.setCellStyle(subStyle);

            Cell s20 = row.createCell(6);
            s20.setCellValue(sales[2].doubleValue());
            s20.setCellStyle(subStyle);

            Cell s100 = row.createCell(7);
            s100.setCellValue(sales[3].doubleValue());
            s100.setCellStyle(subStyle);

            Cell shopee = row.createCell(8);
            shopee.setCellValue(sales[4].doubleValue());
            shopee.setCellStyle(subStyle);

            Cell grab = row.createCell(9);
            grab.setCellValue(sales[5].doubleValue());
            grab.setCellStyle(subStyle);

            Cell lanh = row.createCell(10);
            lanh.setCellValue(sales[6].doubleValue());
            lanh.setCellStyle(subStyle);

            mergeCells(ws, ROW - 1, 11, ROW - 1, 15, "", mergeEmptyStyle);
            stt++;
        }

        // ── Footer ──────────────────────────────────────────────────────────────
        Row foot = ws.createRow(ROW);
        foot.setHeightInPoints(24);
        XSSFCellStyle footStyle = createCellStyle(wb, "009688", true, 11, "FFFFFF", "center");
        setBorder(footStyle);
        mergeCells(ws, ROW, 0, ROW, 10, "Tổng sản phẩm", footStyle);

        Set<Long> mainIngIds = mainIngs.stream()
                .map(PosIngredient::getId)
                .collect(Collectors.toSet());

        BigDecimal totalSoldAll = salesMap.entrySet().stream()
                .filter(e -> mainIngIds.contains(e.getKey()))
                .map(e -> e.getValue()[0].add(e.getValue()[1]).add(e.getValue()[2])
                        .add(e.getValue()[3]).add(e.getValue()[4]).add(e.getValue()[5])
                        .add(e.getValue()[6]))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Cell footCell = foot.createCell(11);
        footCell.setCellValue(totalSoldAll.doubleValue());
        footCell.setCellStyle(footStyle);

        for (int col = 0; col <= 15; col++) {
            Cell cell = foot.getCell(col);
            if (cell == null) cell = foot.createCell(col);
            cell.setCellStyle(footStyle);
        }

        return ROW + 1;
    }

    // ════════════════════════════════════════
    // SHEET DOANH THU
    // ════════════════════════════════════════

    private int buildSheetDoanhThu(XSSFWorkbook wb, XSSFSheet ws, PosShift shift,
                                   int rowStart, String storeName, Long storeId) {

        int[] widths = {4, 24, 8, 8, 8, 8, 10, 9, 14};
        for (int i = 0; i < widths.length; i++)
            ws.setColumnWidth(i, widths[i] * 256);

        List<PosOrder> orders = orderRepo.findByShiftOrderByCreatedAtDesc(shift).stream()
                .filter(o -> o.getStatus() != PosOrderStatus.CANCELLED)
                .collect(Collectors.toList());

        Map<Long, Object[]> prodMap = new LinkedHashMap<>();

        // ============ FILTER PRODUCTS THEO STORE_ID ============
        for (PosProduct p : productRepo.findByStoreIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(storeId))
            prodMap.put(p.getId(), new Object[]{
                    p.getName(), 0, 0, 0, 0, 0, 0,
                    p.getBasePrice(),
                    p.getVatPercent() != null ? p.getVatPercent() : 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
            });
        // =======================================================

        for (PosOrder order : orders) {
            for (PosOrderItem item : orderItemRepo.findByOrder(order)) {
                Object[] d = prodMap.computeIfAbsent(item.getProductId(),
                        id -> new Object[]{item.getProductName(), 0, 0, 0, 0, 0, 0,
                                item.getBasePrice(), 0,
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                int qty = item.getQuantity();
                BigDecimal subtotal = item.getSubtotal() != null ?
                        item.getSubtotal() : BigDecimal.ZERO;

                if (order.getOrderSource() == OrderSource.SHOPEE_FOOD) {
                    d[5] = (int) d[5] + qty;
                    d[10] = ((BigDecimal) d[10]).add(subtotal);
                } else if (order.getOrderSource() == OrderSource.GRAB_FOOD) {
                    d[6] = (int) d[6] + qty;
                    d[11] = ((BigDecimal) d[11]).add(subtotal);
                } else {
                    int disc = item.getDiscountPercent() != null ?
                            item.getDiscountPercent() : 0;
                    if (disc == 0) d[1] = (int) d[1] + qty;
                    else if (disc == 10) d[2] = (int) d[2] + qty;
                    else if (disc == 20) d[3] = (int) d[3] + qty;
                    else if (disc == 100) d[4] = (int) d[4] + qty;
                    else d[1] = (int) d[1] + qty;
                    d[9] = ((BigDecimal) d[9]).add(subtotal);
                }
            }
        }

        ShiftMoney money = calcMoney(shift, orders);
        writeHeaderBlock(wb, ws, shift, money, 10, rowStart, storeName);
        writeSheet2Headers(wb, ws, rowStart + 6);

        int ROW = rowStart + 8;
        int stt = 1;
        BigDecimal grandRevenue = BigDecimal.ZERO;
        BigDecimal grandVat = BigDecimal.ZERO;

        for (Map.Entry<Long, Object[]> e : prodMap.entrySet()) {
            Object[] d = e.getValue();
            int s0 = (int) d[1], s10 = (int) d[2], s20 = (int) d[3], s100 = (int) d[4];
            int shopee = (int) d[5], grab = (int) d[6];
            int vatPct = (int) d[8];
            BigDecimal offlineRev = (BigDecimal) d[9];
            BigDecimal shopeeRev = (BigDecimal) d[10];
            BigDecimal grabRev = (BigDecimal) d[11];
            BigDecimal rev = offlineRev.add(shopeeRev).add(grabRev);
            boolean hasSales = (s0 + s10 + s20 + s100 + shopee + grab) > 0;
            BigDecimal vatAmt = rev.multiply(BigDecimal.valueOf(vatPct))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            grandRevenue = grandRevenue.add(rev);
            grandVat = grandVat.add(vatAmt);

            Row row = ws.createRow(ROW++);
            row.setHeightInPoints(18);
            writeProdCells(wb, row, stt++, (String) d[0],
                    s0, s10, s20, s100, shopee, grab, vatPct, rev, hasSales, stt % 2 == 0);
        }

        Row foot = ws.createRow(ROW);
        foot.setHeightInPoints(26);
        XSSFCellStyle labelStyle = createCellStyle(wb, "009688", true, 11, "FFFFFF", "center");
        XSSFCellStyle vatFoot = createCellStyle(wb, "E65100", true, 10, "FFFFFF", "right");
        XSSFCellStyle revFoot = createCellStyle(wb, "004D40", true, 12, "FFFFFF", "right");
        DataFormat df = wb.createDataFormat();
        vatFoot.setDataFormat(df.getFormat("#,##0"));
        revFoot.setDataFormat(df.getFormat("#,##0"));

        mergeCells(ws, ROW, 0, ROW, 7, "Tổng doanh thu dự kiến", labelStyle);
        Cell cVat = foot.createCell(7);
        cVat.setCellValue(grandVat.doubleValue());
        cVat.setCellStyle(vatFoot);

        Cell cRev = foot.createCell(8);
        cRev.setCellValue(grandRevenue.doubleValue());
        cRev.setCellStyle(revFoot);

        return ROW + 1;
    }

    // ════════════════════════════════════════
    // HEADER BLOCK — ĐÃ CẬP NHẬT
    // ════════════════════════════════════════

    private void writeHeaderBlock(XSSFWorkbook wb, XSSFSheet ws,
                                  PosShift shift, ShiftMoney money,
                                  int maxCol, int rowStart, String storeName) {

        String openT = formatEpoch(shift.getOpenTime(), "HH:mm");
        String closeT = shift.getCloseTime() != null
                ? formatEpoch(shift.getCloseTime(), "HH:mm") : "--:--";

        int r0 = rowStart, r1 = rowStart + 1, r2 = rowStart + 2,
                r3 = rowStart + 3, r4 = rowStart + 4, spacer = rowStart + 5;

        for (int r = rowStart; r <= spacer + 2; r++)
            if (ws.getRow(r) == null) ws.createRow(r);

        for (int r = rowStart; r < rowStart + 5; r++)
            ws.getRow(r).setHeightInPoints(22);
        ws.getRow(spacer).setHeightInPoints(12);

        XSSFCellStyle lblStyle = createCellStyle(wb, "E0F2F1", true, 11, "00796B", "left");
        XSSFCellStyle valStyle = createCellStyle(wb, "E0F2F1", false, 11, "000000", "left");
        XSSFCellStyle rowLblStyle = createCellStyle(wb, "E8F5E9", true, 10, "00796B", "left");
        XSSFCellStyle headerDark = createCellStyle(wb, "00796B", true, 10, "FFFFFF", "center");
        XSSFCellStyle headerMid = createCellStyle(wb, "009688", true, 10, "FFFFFF", "center");
        XSSFCellStyle confirmHdr = createCellStyle(wb, "009688", true, 11, "FFFFFF", "center");
        XSSFCellStyle moneyWhite = createMoneyStyle(wb, "FFFFFF", false);
        XSSFCellStyle moneyBold = createMoneyStyle(wb, "E8F5E9", true);
        XSSFCellStyle shopeeStyle = createMoneyStyle(wb, "FBE9E7", false);
        XSSFCellStyle grabStyle = createMoneyStyle(wb, "E3F2FD", false);
        XSSFCellStyle vatStyle = createMoneyStyle(wb, "FFF9C4", true);
        XSSFCellStyle dashStyle = createCellStyle(wb, "FAFAFA", false, 10, "BBBBBB", "center");
        XSSFCellStyle noteStyle = createCellStyle(wb, "E8F5E9", false, 10, "000000", "left");
        noteStyle.setWrapText(true);
        noteStyle.setVerticalAlignment(VerticalAlignment.TOP);

        // ==================== THÊM DÒNG STORE ====================
        String[][] info = {
                {"Store", storeName},
                {"Ngày", shift.getShiftDate()},
                {"Tên NV", shift.getStaffName()},
                {"Thời gian", "#" + shift.getId() + "  " + openT + " - " + closeT}
        };

        for (int i = 0; i < info.length; i++) {
            Row row = ws.getRow(rowStart + i);
            Cell lbl = row.createCell(0);
            lbl.setCellValue(info[i][0]);
            lbl.setCellStyle(lblStyle);

            Cell val = row.createCell(1);
            val.setCellValue(info[i][1]);
            val.setCellStyle(valStyle);
        }

        final int DC = 4;
        mergeCells(ws, r0, DC, r0, DC, "Đầu ca", headerDark);
        mergeCells(ws, r0, DC + 1, r0, DC + 2, "Cuối ca", headerDark);
        ws.getRow(r0).createCell(DC + 3).setCellValue("Tổng kết ca");
        ws.getRow(r0).getCell(DC + 3).setCellStyle(headerMid);
        ws.getRow(r0).createCell(DC + 4).setCellValue("DT dự kiến");
        ws.getRow(r0).getCell(DC + 4).setCellStyle(headerMid);
        mergeCells(ws, r0, DC + 5, r0, DC + 8, "Xác nhận", confirmHdr);

        mergeCellsNum(ws, r1, DC + 3, r4, DC + 3,
                money.closingCash().subtract(money.openingCash())
                        .add(money.transferAmount()).add(money.appRevenue()).doubleValue(),
                moneyBold, wb);
        mergeCellsNum(ws, r1, DC + 4, r4, DC + 4, money.expectedRevenue().doubleValue(), moneyBold, wb);

        String noteVal = shift.getNote() != null ? shift.getNote() : "";
        mergeCells(ws, r1, DC + 5, r4, DC + 8, noteVal, noteStyle);

        Row r1Row = ws.getRow(r1);
        r1Row.createCell(3).setCellValue("Tiền mặt"); r1Row.getCell(3).setCellStyle(rowLblStyle);
        Cell d1 = r1Row.createCell(DC); d1.setCellValue(money.openingCash().doubleValue()); d1.setCellStyle(moneyWhite);
        mergeCellsNum(ws, r1, DC + 1, r1, DC + 2, money.closingCash().doubleValue(), moneyWhite, wb);

        Row r2Row = ws.getRow(r2);
        r2Row.createCell(3).setCellValue("Momo"); r2Row.getCell(3).setCellStyle(rowLblStyle);
        Cell d2 = r2Row.createCell(DC); d2.setCellValue("-"); d2.setCellStyle(dashStyle);
        mergeCellsNum(ws, r2, DC + 1, r2, DC + 2, money.transferAmount().doubleValue(), moneyBold, wb);

        Row r3Row = ws.getRow(r3);
        r3Row.createCell(3).setCellValue("App"); r3Row.getCell(3).setCellStyle(rowLblStyle);
        Cell d3 = r3Row.createCell(DC); d3.setCellValue("-"); d3.setCellStyle(dashStyle);
        setNumCellStyle(r3Row, DC + 1, money.shopeeRevenue(), wb, shopeeStyle);
        setNumCellStyle(r3Row, DC + 2, money.grabRevenue(), wb, grabStyle);

        Row r4Row = ws.getRow(r4);
        r4Row.createCell(3).setCellValue("VAT"); r4Row.getCell(3).setCellStyle(rowLblStyle);
        Cell d4 = r4Row.createCell(DC); d4.setCellValue("-"); d4.setCellStyle(dashStyle);
        mergeCellsNum(ws, r4, DC + 1, r4, DC + 2, money.totalVat().doubleValue(), vatStyle, wb);
    }

    // Các method còn lại (writeSheet1Headers, writeSheet2Headers, writeProdCells,
    // createCellStyle, createMoneyStyle, mergeCells, setNumCellStyle, mergeCellsNum,
    // hexToBytes, formatEpoch, isProductSinglePrice, calcMoney, nvlBD, nvlInt, fmtDecimal...)
    // giữ nguyên hoàn toàn như code bạn cung cấp.

    private void writeSheet1Headers(XSSFWorkbook wb, XSSFSheet ws, int headerRow) {
        // giữ nguyên như cũ
        Row r6 = ws.getRow(headerRow); if (r6 == null) r6 = ws.createRow(headerRow);
        Row r7 = ws.getRow(headerRow + 1); if (r7 == null) r7 = ws.createRow(headerRow + 1);
        r6.setHeightInPoints(22);
        r7.setHeightInPoints(30);

        XSSFCellStyle hTeal = createCellStyle(wb, "00796B", true, 9, "FFFFFF", "center");
        XSSFCellStyle hTealM = createCellStyle(wb, "009688", true, 9, "FFFFFF", "center");
        XSSFCellStyle hOrange = createCellStyle(wb, "FF5722", true, 9, "FFFFFF", "center");

        mergeCells(ws, headerRow, 0, headerRow + 1, 0, "STT", hTeal);
        mergeCells(ws, headerRow, 1, headerRow + 1, 1, "Tên Hàng", hTeal);
        mergeCells(ws, headerRow, 2, headerRow, 3, "Đầu ca", hTealM);
        mergeCells(ws, headerRow, 4, headerRow, 10, "Bán", hOrange);
        mergeCells(ws, headerRow, 11, headerRow + 1, 11, "Nhập", hTealM);
        mergeCells(ws, headerRow, 12, headerRow, 13, "Cuối ca", hTealM);
        mergeCells(ws, headerRow, 14, headerRow + 1, 14, "Trạng thái", hTeal);
        mergeCells(ws, headerRow, 15, headerRow + 1, 15, "SL", hTeal);

        String[] r7vals = {null, null, "Bịch", "Lẻ", "0%", "10%", "20%", "100%",
                "ShopeeFood", "GrabFood", "Lạnh", null, "Bịch", "Lẻ", null, null};
        for (int i = 0; i < r7vals.length; i++) {
            if (r7vals[i] == null) continue;
            Cell c = r7.createCell(i);
            c.setCellValue(r7vals[i]);
            c.setCellStyle((i >= 4 && i <= 10) ? hOrange : hTealM);
        }
    }

    private void writeSheet2Headers(XSSFWorkbook wb, XSSFSheet ws, int headerRow) {
        // giữ nguyên như cũ
        Row r6 = ws.getRow(headerRow); if (r6 == null) r6 = ws.createRow(headerRow);
        Row r7 = ws.getRow(headerRow + 1); if (r7 == null) r7 = ws.createRow(headerRow + 1);
        r6.setHeightInPoints(22);
        r7.setHeightInPoints(28);

        XSSFCellStyle hTeal = createCellStyle(wb, "00796B", true, 9, "FFFFFF", "center");
        XSSFCellStyle hOrange = createCellStyle(wb, "BF360C", true, 9, "FFFFFF", "center");

        mergeCells(ws, headerRow, 0, headerRow + 1, 0, "STT", hTeal);
        mergeCells(ws, headerRow, 1, headerRow + 1, 1, "Tên sản phẩm", hTeal);
        mergeCells(ws, headerRow, 2, headerRow, 7, "Bán", hOrange);
        mergeCells(ws, headerRow, 8, headerRow + 1, 8, "Doanh thu dự kiến", hTeal);

        String[] bans = {"0%", "10%", "20%", "100%", "ShopeeFood", "GrabFood"};
        for (int i = 0; i < bans.length; i++) {
            Cell c = r7.createCell(i + 2);
            c.setCellValue(bans[i]);
            c.setCellStyle(hOrange);
        }
    }

    // ==================== HELPERS (giữ nguyên) ====================
    private void applyRowStyle(Row row, XSSFCellStyle style, int colCount) {
        for (int col = 0; col < colCount; col++) {
            Cell cell = row.getCell(col);
            if (cell == null) cell = row.createCell(col);
            cell.setCellStyle(style);
        }
    }

    private void setBorder(XSSFCellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setTopBorderColor(IndexedColors.BLACK.getIndex());
        style.setBottomBorderColor(IndexedColors.BLACK.getIndex());
        style.setLeftBorderColor(IndexedColors.BLACK.getIndex());
        style.setRightBorderColor(IndexedColors.BLACK.getIndex());
    }

    private void writeProdCells(XSSFWorkbook wb, Row row, int stt, String name,
                                int s0, int s10, int s20, int s100, int shopee, int grab,
                                int vatPct, BigDecimal rev, boolean hasSales, boolean even) {
        String rowBg = even ? "F5F5F5" : "FFFFFF";
        String revBg = hasSales ? "FBE9E7" : rowBg;
        String revFg = hasSales ? "BF360C" : "AAAAAA";

        XSSFCellStyle cs = createCellStyle(wb, rowBg, false, 10, "000000", "center");
        XSSFCellStyle csName = createCellStyle(wb, rowBg, true, 10, "000000", "left");
        XSSFCellStyle csRev = createCellStyle(wb, revBg, hasSales, 10, revFg, "right");
        DataFormat df = wb.createDataFormat();
        csRev.setDataFormat(df.getFormat("#,##0"));

        row.createCell(0).setCellValue(stt); row.getCell(0).setCellStyle(cs);
        Cell nc = row.createCell(1); nc.setCellValue(name); nc.setCellStyle(csName);
        int[] vals = {s0, s10, s20, s100, shopee, grab};
        for (int i = 0; i < vals.length; i++) {
            Cell c = row.createCell(i + 2); c.setCellValue(vals[i]); c.setCellStyle(cs);
        }
        Cell rc = row.createCell(8); rc.setCellValue(rev.doubleValue()); rc.setCellStyle(csRev);
    }

    private XSSFCellStyle createCellStyle(XSSFWorkbook wb, String bgHex, boolean bold,
                                          int fontSize, String fgHex, String align) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(hexToBytes(bgHex), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        XSSFFont font = wb.createFont();
        font.setFontName("Arial");
        font.setBold(bold);
        font.setFontHeightInPoints((short) fontSize);
        font.setColor(new XSSFColor(hexToBytes(fgHex), null));
        style.setFont(font);
        style.setAlignment("center".equals(align) ? HorizontalAlignment.CENTER :
                ("right".equals(align) ? HorizontalAlignment.RIGHT : HorizontalAlignment.LEFT));
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setTopBorderColor(IndexedColors.GREY_40_PERCENT.index);
        style.setBottomBorderColor(IndexedColors.GREY_40_PERCENT.index);
        return style;
    }

    private XSSFCellStyle createMoneyStyle(XSSFWorkbook wb, String bgHex, boolean bold) {
        XSSFCellStyle cs = createCellStyle(wb, bgHex, bold, 10, "000000", "center");
        cs.setAlignment(HorizontalAlignment.CENTER);
        cs.setVerticalAlignment(VerticalAlignment.CENTER);
        DataFormat df = wb.createDataFormat();
        cs.setDataFormat(df.getFormat("#,##0"));
        return cs;
    }

    private void mergeCells(XSSFSheet ws, int r1, int c1, int r2, int c2,
                            Object value, XSSFCellStyle style) {
        if (r1 != r2 || c1 != c2) ws.addMergedRegion(new CellRangeAddress(r1, r2, c1, c2));
        Row row = ws.getRow(r1); if (row == null) row = ws.createRow(r1);
        for (int r = r1; r <= r2; r++) {
            Row mr = ws.getRow(r); if (mr == null) mr = ws.createRow(r);
            for (int c = c1; c <= c2; c++) {
                Cell mc = mr.getCell(c); if (mc == null) mc = mr.createCell(c);
                mc.setCellStyle(style);
            }
        }
        Cell topLeft = ws.getRow(r1).getCell(c1);
        if (value instanceof String) topLeft.setCellValue((String) value);
        else if (value instanceof Number) topLeft.setCellValue(((Number) value).doubleValue());
    }

    private void setNumCellStyle(Row row, int col, BigDecimal val,
                                 XSSFWorkbook wb, XSSFCellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(val != null ? val.doubleValue() : 0);
        c.setCellStyle(style);
    }

    private void mergeCellsNum(XSSFSheet ws, int r1, int c1, int r2, int c2,
                               double val, XSSFCellStyle style, XSSFWorkbook wb) {
        if (r1 != r2 || c1 != c2) ws.addMergedRegion(new CellRangeAddress(r1, r2, c1, c2));
        Row row = ws.getRow(r1); if (row == null) row = ws.createRow(r1);
        Cell cell = row.createCell(c1);
        cell.setCellValue(val);
        cell.setCellStyle(style);
    }

    private static byte[] hexToBytes(String hex) {
        return new byte[]{
                (byte) Integer.parseInt(hex.substring(0, 2), 16),
                (byte) Integer.parseInt(hex.substring(2, 4), 16),
                (byte) Integer.parseInt(hex.substring(4, 6), 16)
        };
    }

    private String formatEpoch(Long epoch, String pattern) {
        if (epoch == null) return "";
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(pattern));
    }

    private boolean isProductSinglePrice(Long productId) {
        return productRepo.findByIdWithCategory(productId)
                .map(p -> Boolean.TRUE.equals(p.getCategory().getSinglePrice()))
                .orElse(false);
    }

    private ShiftMoney calcMoney(PosShift shift, List<PosOrder> orders) {
        BigDecimal openCash = nvlBD(shift.getOpeningCash());
        BigDecimal closeCash = nvlBD(shift.getClosingCash());
        BigDecimal transfer = nvlBD(shift.getTransferAmount());

        BigDecimal shopeeRev = orders.stream()
                .filter(o -> o.getOrderSource() == OrderSource.SHOPEE_FOOD)
                .map(PosOrder::getFinalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grabRev = orders.stream()
                .filter(o -> o.getOrderSource() == OrderSource.GRAB_FOOD)
                .map(PosOrder::getFinalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal appRev = shopeeRev.add(grabRev);

        BigDecimal totalVat = orders.stream()
                .flatMap(o -> orderItemRepo.findByOrder(o).stream())
                .map(item -> item.getVatAmount() != null ? item.getVatAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expectedRev = orders.stream()
                .map(PosOrder::getFinalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal subTotal = closeCash.subtract(openCash).add(transfer);
        BigDecimal grandTotal = subTotal.add(appRev);
        BigDecimal actualRev = closeCash.add(transfer).add(appRev).subtract(openCash);
        BigDecimal netRevenue = grandTotal.subtract(openCash);

        return new ShiftMoney(openCash, closeCash, transfer,
                shopeeRev, grabRev, appRev, subTotal,
                grandTotal, actualRev, expectedRev, netRevenue, totalVat);
    }

    private BigDecimal nvlBD(BigDecimal val) {
        return val != null ? val : BigDecimal.ZERO;
    }

    private int nvlInt(Integer val) {
        return val != null ? val : 0;
    }

    private int nvlInt(Integer val, int defaultVal) {
        return val != null ? val : defaultVal;
    }

    private String fmtDecimal(BigDecimal val) {
        if (val == null) return "0";
        BigDecimal stripped = val.stripTrailingZeros();
        if (stripped.scale() <= 0) return stripped.toPlainString();
        return stripped.toPlainString();
    }

    private void writeDataCells(XSSFWorkbook wb, Row row, boolean even,
                                int stt, String name,
                                int openPack, BigDecimal openUnit,
                                BigDecimal s0, BigDecimal s10, BigDecimal s20, BigDecimal s100,
                                BigDecimal shopee, BigDecimal grab, BigDecimal lanh,
                                int imp, int closePack, BigDecimal closeUnit,
                                String status, String slVal, int diffSign) {

        String rowBg = even ? "F5F5F5" : "FFFFFF";
        String statusBg = diffSign == 0 ? rowBg : (diffSign > 0 ? "E8F5E9" : "FFEBEE");
        String statusFg = diffSign == 0 ? "000000" : (diffSign > 0 ? "2E7D32" : "C62828");

        XSSFCellStyle cs = createCellStyle(wb, rowBg, false, 10, "000000", "center");
        XSSFCellStyle csName = createCellStyle(wb, rowBg, true, 10, "000000", "left");
        XSSFCellStyle csStat = createCellStyle(wb, statusBg, true, 10, statusFg, "center");

        XSSFCellStyle csDecimal = createCellStyle(wb, rowBg, false, 10, "000000", "center");
        DataFormat df = wb.createDataFormat();
        csDecimal.setDataFormat(df.getFormat("#,##0.##"));

        // Integer columns: STT, Open Pack, Import, Close Pack
        int[] intCols = {0, 2, 11, 12};
        int[] intValsA = {stt, openPack, imp, closePack};
        for (int k = 0; k < intCols.length; k++) {
            Cell c = row.createCell(intCols[k]);
            c.setCellValue(intValsA[k]);
            c.setCellStyle(cs);
        }

        // Name column
        Cell cName = row.createCell(1);
        cName.setCellValue(name);
        cName.setCellStyle(csName);

        // Decimal columns: Open Unit, Close Unit
        Cell cOpenUnit = row.createCell(3);
        cOpenUnit.setCellValue(openUnit.doubleValue());
        cOpenUnit.setCellStyle(csDecimal);

        Cell cCloseUnit = row.createCell(13);
        cCloseUnit.setCellValue(closeUnit.doubleValue());
        cCloseUnit.setCellStyle(csDecimal);

        // Sales columns (0%, 10%, 20%, 100%, ShopeeFood, GrabFood, Lạnh)
        BigDecimal[] saleCols = {s0, s10, s20, s100, shopee, grab, lanh};
        for (int k = 0; k < saleCols.length; k++) {
            Cell c = row.createCell(4 + k);
            c.setCellValue(saleCols[k].doubleValue());
            c.setCellStyle(csDecimal);
        }

        // Status columns
        Cell cSt = row.createCell(14);
        cSt.setCellValue(status);
        cSt.setCellStyle(csStat);

        Cell cSl = row.createCell(15);
        cSl.setCellValue(slVal);
        cSl.setCellStyle(csStat);
    }

    private record ShiftMoney(
            BigDecimal openingCash, BigDecimal closingCash, BigDecimal transferAmount,
            BigDecimal shopeeRevenue, BigDecimal grabRevenue, BigDecimal appRevenue,
            BigDecimal subTotal, BigDecimal grandTotal, BigDecimal actualRevenue,
            BigDecimal expectedRevenue, BigDecimal netRevenue,
            BigDecimal totalVat
    ) {
    }
}