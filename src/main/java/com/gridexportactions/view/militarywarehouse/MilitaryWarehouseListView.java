package com.gridexportactions.view.militarywarehouse;

import com.gridexportactions.entity.MilitaryWarehouse;
import com.gridexportactions.view.main.MainView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.download.DownloadFormat;
import io.jmix.flowui.download.Downloader;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.view.*;
import io.jmix.gridexportflowui.action.ExcelExportAction;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Route(value = "military-warehouses", layout = MainView.class)
@ViewController(id = "MilitaryWarehouse.list")
@ViewDescriptor(path = "military-warehouse-list-view.xml")
@LookupComponent("militaryWarehousesDataGrid")
@DialogMode(width = "64em")
public class MilitaryWarehouseListView extends StandardListView<MilitaryWarehouse> {

    @ViewComponent private DataGrid<MilitaryWarehouse> militaryWarehousesDataGrid;
    @ViewComponent("militaryWarehousesDataGrid.excelExport") private ExcelExportAction excelExport;
    @ViewComponent("militaryWarehousesDc") private CollectionContainer<MilitaryWarehouse> militaryWarehousesDc;

    @Autowired private Downloader downloader;
    @Autowired private DataManager dataManager;
    @Autowired private Notifications notifications;
    @Autowired private ResourceLoader resourceLoader;

    @Value("${app.templates.militaryWarehouse:file:./app-templates/military-warehouse-template.xlsx}")
    private String templatePath;

    // 'in' = ô neo tiêu đề (nếu cần)
    @Value("${app.templates.militaryWarehouse.anchorName:in}")
    private String templateAnchorName;

    // 'tableValue' = dải 1 hàng mẫu để bắt đầu ghi DATA
    @Value("${app.templates.militaryWarehouse.tableAnchorName:tableValue}")
    private String tableAnchorName;

    // KHAI BÁO SPAN CHO CỘT (bao nhiêu ô sẽ merge nếu phải tạo thêm block)
    private final Map<String, Integer> colSpanPlan = new LinkedHashMap<>() {{
        put("loai", 1);
        put("ten", 2);
        put("trangThai", 1);
        put("nhaSanXuat", 2);
        put("soSeri", 1);
        put("soLuong", 1);
        put("ngayTiepNhan", 1);
        put("hanSuDungBaoDuong", 2);
        put("coNong", 1);
        put("ghiChu", 3);
    }};

    private final Map<String, Function<MilitaryWarehouse, String>> exportValueProviders = new HashMap<>();
    private List<Grid.Column<MilitaryWarehouse>> uiOrderedColumns = new ArrayList<>();

    // ===== Lifecycle =====
    @Subscribe
    public void onReady(ReadyEvent e) {
        uiOrderedColumns = new ArrayList<>(militaryWarehousesDataGrid.getColumns());
        militaryWarehousesDataGrid.addColumnReorderListener(ev -> uiOrderedColumns = ev.getColumns());
    }

    private List<Grid.Column<MilitaryWarehouse>> getVisibleColumnsInUiOrder() {
        List<Grid.Column<MilitaryWarehouse>> src =
                (uiOrderedColumns == null || uiOrderedColumns.isEmpty())
                        ? militaryWarehousesDataGrid.getColumns()
                        : uiOrderedColumns;
        return src.stream().filter(Grid.Column::isVisible).collect(Collectors.toList());
    }

    // ===== Named Range helpers =====
    private static class Anchor {
        final Sheet sheet; final int row; final int col;
        Anchor(Sheet s, int r, int c) { sheet = s; row = r; col = c; }
    }

    private static Anchor resolveAnchor(Workbook wb, String name) {
        Name found = null;
        try { found = wb.getName(name); } catch (UnsupportedOperationException ignore) {}
        if (found == null) {
            try {
                for (Name n : wb.getAllNames()) {
                    if (name.equalsIgnoreCase(n.getNameName())) { found = n; break; }
                }
            } catch (UnsupportedOperationException ignored) {}
        }
        if (found != null && found.getRefersToFormula() != null) {
            try {
                AreaReference ar = new AreaReference(found.getRefersToFormula(), wb.getSpreadsheetVersion());
                CellReference first = ar.getFirstCell();
                Sheet sheet = (first.getSheetName() != null)
                        ? wb.getSheet(first.getSheetName())
                        : (found.getSheetIndex() >= 0 ? wb.getSheetAt(found.getSheetIndex()) : wb.getSheetAt(0));
                return new Anchor(sheet, first.getRow(), first.getCol());
            } catch (IllegalArgumentException ignore) {}
        }
        // Fallback: sheet đầu, cột B, hàng 10
        Sheet sheet = wb.getSheet("Export");
        if (sheet == null) sheet = wb.getSheetAt(0);
        return new Anchor(sheet, 9, 1);
    }

    // ====== Helpers đọc layout merge từ NamedRange `tableValue` ======
    private static class TableArea {
        final Sheet sheet; final int row; final int fromCol; final int toCol;
        TableArea(Sheet s, int r, int fc, int tc) { sheet = s; row = r; fromCol = fc; toCol = tc; }
    }
    private static class Segment { final int from, to; Segment(int f, int t){from=f;to=t;} }

    private static TableArea resolveTableArea(Workbook wb, String name) {
        Name n = null;
        try { n = wb.getName(name); } catch (UnsupportedOperationException ignore) {}
        if (n == null || n.getRefersToFormula() == null) return null;

        AreaReference ar = new AreaReference(n.getRefersToFormula(), wb.getSpreadsheetVersion());
        CellReference first = ar.getFirstCell();
        CellReference last  = ar.getLastCell();
        Sheet sheet = (first.getSheetName() != null)
                ? wb.getSheet(first.getSheetName())
                : (n.getSheetIndex() >= 0 ? wb.getSheetAt(n.getSheetIndex()) : wb.getSheetAt(0));
        int fromCol = Math.min(first.getCol(), last.getCol());
        int toCol   = Math.max(first.getCol(), last.getCol());
        return new TableArea(sheet, first.getRow(), fromCol, toCol);
    }

    private static CellRangeAddress findMergedRegionStartingAt(Sheet sh, int row, int col) {
        for (CellRangeAddress r : sh.getMergedRegions()) {
            if (r.getFirstRow() <= row && r.getLastRow() >= row
                    && r.getFirstColumn() == col) return r;
        }
        return null;
    }
    private static CellRangeAddress findMergedRegionContaining(Sheet sh, int row, int col) {
        for (CellRangeAddress r : sh.getMergedRegions()) {
            if (r.isInRange(row, col)) return r;
        }
        return null;
    }
    private static List<Segment> getSegmentsFromTemplateRow(Sheet sh, int row, int fromCol, int toCol) {
        List<Segment> segs = new ArrayList<>();
        int c = fromCol;
        while (c <= toCol) {
            CellRangeAddress rStart = findMergedRegionStartingAt(sh, row, c);
            if (rStart != null) {
                segs.add(new Segment(c, rStart.getLastColumn()));
                c = rStart.getLastColumn() + 1;
                continue;
            }
            CellRangeAddress rAny = findMergedRegionContaining(sh, row, c);
            if (rAny != null) {
                c = rAny.getLastColumn() + 1;
                continue;
            }
            segs.add(new Segment(c, c));
            c++;
        }
        return segs;
    }
    private static boolean hasExactMergedRegion(Sheet sh, int row, int from, int to) {
        for (CellRangeAddress r : sh.getMergedRegions()) {
            if (r.getFirstRow() == row && r.getLastRow() == row
                    && r.getFirstColumn() == from && r.getLastColumn() == to) return true;
        }
        return false;
    }
    private static Row getOrCreateRow(Sheet sheet, int rowIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        return row;
    }
    private static Cell getOrCreateCell(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) cell = row.createCell(colIndex);
        return cell;
    }
    private static void writeIntoSegment(Sheet sh, int rowIdx, int from, int to, String val, CellStyle st) {
        Row row = getOrCreateRow(sh, rowIdx);
        for (int c = from; c <= to; c++) {
            Cell cell = row.getCell(c);
            if (cell == null) cell = row.createCell(c);
            if (c == from) cell.setCellValue(val == null ? "" : val);
            else cell.setBlank();
            if (st != null) cell.setCellStyle(st);
        }
        if (to > from && !hasExactMergedRegion(sh, rowIdx, from, to)) {
            sh.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, from, to));
        }
    }

    // ===== Gộp cột động trong GRID (UI) – không ảnh hưởng export =====
    @Subscribe("mergeColumnsBtn")
    public void onMergeColumnsBtnClick(ClickEvent<Button> event) {
        Dialog dlg = new Dialog();
        dlg.setHeaderTitle("Gộp cột");

        List<String> availableKeys = militaryWarehousesDataGrid.getColumns().stream()
                .map(Grid.Column::getKey).filter(Objects::nonNull).collect(Collectors.toList());

        CheckboxGroup<String> pick = new CheckboxGroup<>();
        pick.setLabel("Chọn các cột để gộp (theo thứ tự chọn)");
        pick.setItems(availableKeys);
        pick.select("ten", "loai");

        TextField header = new TextField("Tên cột mới");
        header.setValue("Gộp");

        String fixedDelimiter = " ";

        Button create = new Button("Tạo cột", e -> {
            List<String> selected = new ArrayList<>(pick.getSelectedItems());
            if (selected.size() < 2) { dlg.close(); return; }

            String newKey = "merged_" + System.currentTimeMillis();
            Grid.Column<MilitaryWarehouse> col =
                    militaryWarehousesDataGrid.addColumn(mw -> joinByKeys(mw, selected, fixedDelimiter));
            col.setKey(newKey);
            col.setHeader(header.getValue());
            col.setAutoWidth(true);
            col.setVisible(true);

            excelExport.addColumnValueProvider(newKey,
                    ctx -> joinByKeys((MilitaryWarehouse) ctx.getEntity(), selected, fixedDelimiter));
            exportValueProviders.put(newKey, mw -> joinByKeys(mw, selected, fixedDelimiter));

            uiOrderedColumns = new ArrayList<>(militaryWarehousesDataGrid.getColumns());
            dlg.close();
        });

        dlg.add(pick, header, create);
        dlg.open();
    }

    // ===== EXPORT: Data bắt đầu NGAY tại 'tableValue'; thiếu cột thì tự merge thêm =====
    @Subscribe("exportByTemplateBtn")
    public void onExportByTemplateBtnClick(ClickEvent<Button> event) {
        Resource res = resourceLoader.getResource(templatePath);
        if (!res.exists()) {
            notifications.create("Không tìm thấy template: " + templatePath)
                    .withType(Notifications.Type.ERROR).show();
            return;
        }

        try (InputStream is = res.getInputStream();
             Workbook wb = WorkbookFactory.create(is);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            // Đọc hàng mẫu từ NamedRange cấu hình (không hard-code)
            TableArea ta = resolveTableArea(wb, tableAnchorName);
            if (ta == null) {
                notifications.create("Không tìm thấy Named Range: " + tableAnchorName)
                        .withType(Notifications.Type.ERROR).show();
                return;
            }
            Sheet sheet = ta.sheet;

            // Các block (segment) sẵn có từ hàng template
            List<Segment> baseSegments = getSegmentsFromTemplateRow(sheet, ta.row, ta.fromCol, ta.toCol);
            if (baseSegments.isEmpty()) {
                notifications.create("Hàng mẫu (" + tableAnchorName + ") không có cột nào.")
                        .withType(Notifications.Type.ERROR).show();
                return;
            }

            // Dữ liệu theo thứ tự cột đang hiển thị
            List<Grid.Column<MilitaryWarehouse>> visibleCols = getVisibleColumnsInUiOrder();

            List<MilitaryWarehouse> all = dataManager.load(MilitaryWarehouse.class)
                    .query("select e from MilitaryWarehouse e")
                    .fetchPlan("_base")
                    .list();
            Comparator<MilitaryWarehouse> cmp = buildComparatorFromGridSort(militaryWarehousesDataGrid.getSortOrder());
            if (cmp != null) all.sort(cmp);

            CellStyle bodyStyle = buildBodyStyle(wb);

            // Data bắt đầu NGAY tại hàng tableValue
            int rowIdx = ta.row;
            int stt = 1;

            for (MilitaryWarehouse mw : all) {
                // Danh sách value: [STT] + các cột UI
                List<String> values = new ArrayList<>();
                values.add(String.valueOf(stt));
                for (Grid.Column<MilitaryWarehouse> col : visibleCols) {
                    values.add(valueFor(mw, col));
                }

                // Nếu template thiếu block, tạo thêm block nối tiếp bên phải (merge theo colSpanPlan)
                List<Segment> segsForThisRow = extendSegmentsIfNeeded(
                        baseSegments, values.size(), ta, visibleCols
                );

                // Ghi vào từng block
                int count = Math.min(values.size(), segsForThisRow.size());
                for (int i = 0; i < count; i++) {
                    Segment seg = segsForThisRow.get(i);
                    writeIntoSegment(sheet, rowIdx, seg.from, seg.to, values.get(i), bodyStyle);
                }
                // Nếu còn block thừa → fill rỗng
                for (int i = count; i < segsForThisRow.size(); i++) {
                    Segment seg = segsForThisRow.get(i);
                    writeIntoSegment(sheet, rowIdx, seg.from, seg.to, "", bodyStyle);
                }

                stt++;
                rowIdx++;
            }

            wb.write(bos);
            downloader.download(bos.toByteArray(), "military-warehouses.xlsx", DownloadFormat.XLSX);

        } catch (Exception ex) {
            notifications.create("Xuất theo template lỗi: " + ex.getMessage())
                    .withType(Notifications.Type.ERROR).show();
        }
    }

    /**
     * Nếu số value cần in > số segment trong template, tạo thêm segment nối tiếp bên phải.
     * span cho từng cột lấy từ colSpanPlan (mặc định 1). Chỉ merge trên HÀNG DATA, không đụng vào hàng template.
     */
    private List<Segment> extendSegmentsIfNeeded(List<Segment> base, int needCount,
                                                 TableArea ta,
                                                 List<Grid.Column<MilitaryWarehouse>> visibleCols) {
        List<Segment> out = new ArrayList<>(base);
        if (out.size() >= needCount) return out;

        int nextCol = out.isEmpty() ? ta.fromCol : (out.get(out.size() - 1).to + 1);
        // i = 0 là STT → span = 1; i >= 1 map tới visibleCols.get(i-1)
        for (int i = out.size(); i < needCount; i++) {
            int span;
            if (i == 0) {
                span = 1;
            } else {
                int idx = i - 1;
                if (idx >= 0 && idx < visibleCols.size()) {
                    String key = Objects.toString(visibleCols.get(idx).getKey(), "");
                    span = Math.max(1, colSpanPlan.getOrDefault(key, 1));
                } else {
                    span = 1;
                }
            }
            int from = nextCol;
            int to = nextCol + span - 1;
            out.add(new Segment(from, to));
            nextCol = to + 1;
        }
        return out;
    }

    // ===== Upload template =====
    @Subscribe("uploadTemplateBtn")
    public void onUploadTemplateBtnClick(ClickEvent<Button> event) {
        if (!templatePath.startsWith("file:")) {
            notifications.create(
                            "Đường dẫn template hiện tại không ghi đè được: " + templatePath +
                                    "\nHãy đặt app.templates.militaryWarehouse về dạng file:, ví dụ: file:./app-templates/military-warehouse-template.xlsx")
                    .withType(Notifications.Type.WARNING).show();
            return;
        }
        Dialog dlg = new Dialog();
        dlg.setHeaderTitle("Tải lên template (.xlsx)");

        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(".xlsx");
        upload.addSucceededListener(succ -> {
            try (InputStream in = buffer.getInputStream()) {
                Resource r = resourceLoader.getResource(templatePath);
                File target = r.getFile();
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);

                notifications.create("Đã cập nhật template: " + target.getAbsolutePath())
                        .withType(Notifications.Type.SUCCESS).show();
                dlg.close();
            } catch (Exception ex) {
                notifications.create("Tải template lỗi: " + ex.getMessage())
                        .withType(Notifications.Type.ERROR).show();
            }
        });

        dlg.add(upload);
        dlg.open();
    }

    // ===== Sort helper theo Grid =====
    private Comparator<MilitaryWarehouse> buildComparatorFromGridSort(List<GridSortOrder<MilitaryWarehouse>> orders) {
        if (orders == null || orders.isEmpty()) return null;
        Comparator<MilitaryWarehouse> combined = null;
        for (GridSortOrder<MilitaryWarehouse> so : orders) {
            Grid.Column<MilitaryWarehouse> col = so.getSorted();
            if (col == null) continue;

            Comparator<MilitaryWarehouse> c = Comparator.comparing(
                    mw -> {
                        String v = valueFor(mw, col);
                        return v == null ? "" : v.toLowerCase(Locale.ROOT);
                    }
            );
            if (so.getDirection() == SortDirection.DESCENDING) c = c.reversed();
            combined = (combined == null) ? c : combined.thenComparing(c);
        }
        return combined;
    }

    // ===== Styles =====
    private static CellStyle buildBodyStyle(Workbook wb) {
        CellStyle st = wb.createCellStyle();
        st.setWrapText(true);
        st.setAlignment(HorizontalAlignment.LEFT);
        st.setVerticalAlignment(VerticalAlignment.TOP);
        st.setBorderTop(BorderStyle.THIN);
        st.setTopBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        st.setBorderBottom(BorderStyle.THIN);
        st.setBottomBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        st.setBorderLeft(BorderStyle.THIN);
        st.setLeftBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        st.setBorderRight(BorderStyle.THIN);
        st.setRightBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        return st;
    }

    private static void setCellNumber(Row row, int colIndex, double val) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) cell = row.createCell(colIndex);
        cell.setCellValue(val);
    }

    private static String headerTextFromUI(Grid.Column<?> col) {
        try {
            var m = col.getClass().getMethod("getHeaderText");
            Object o = m.invoke(col);
            if (o instanceof String s && !s.isBlank()) return s;
        } catch (Exception ignored) {}
        try {
            var m = col.getClass().getMethod("getHeader");
            Object comp = m.invoke(col);
            if (comp instanceof HasText ht && ht.getText() != null && !ht.getText().isBlank())
                return ht.getText();
        } catch (Exception ignored) {}
        try {
            var m2 = col.getClass().getMethod("getHeaderComponent");
            Object comp2 = m2.invoke(col);
            if (comp2 instanceof HasText ht2 && ht2.getText() != null && !ht2.getText().isBlank())
                return ht2.getText();
        } catch (Exception ignored) {}
        String k = col.getKey();
        return k == null ? "" : k;
    }

    private String valueFor(MilitaryWarehouse mw, Grid.Column<MilitaryWarehouse> col) {
        String key = col.getKey();
        if (key == null) return "";

        Function<MilitaryWarehouse, String> fn = exportValueProviders.get(key);
        if (fn != null) return safe(fn.apply(mw));

        return switch (key) {
            case "loai" -> nz(mw.getLoai());
            case "ten" -> nz(mw.getTen());
            case "trangThai" -> nz(mw.getTrangThai());
            case "nhaSanXuat" -> nz(mw.getNhaSanXuat());
            case "soSeri" -> nz(mw.getSoSeri());
            case "soLuong" -> mw.getSoLuong() == null ? "" : String.valueOf(mw.getSoLuong());
            case "ngayTiepNhan" -> fmt(mw.getNgayTiepNhan());
            case "hanSuDungBaoDuong" -> fmt(mw.getHanSuDungBaoDuong());
            case "coNong" -> nz(mw.getCoNong());
            case "ghiChu" -> nz(mw.getGhiChu());
            default -> "";
        };
    }

    private static String joinByKeys(MilitaryWarehouse mw, List<String> keys, String delim) {
        String d = (delim == null ? " " : delim);
        return keys.stream()
                .map(k -> switch (k) {
                    case "loai"               -> nz(mw.getLoai());
                    case "ten"                -> nz(mw.getTen());
                    case "trangThai"          -> nz(mw.getTrangThai());
                    case "nhaSanXuat"         -> nz(mw.getNhaSanXuat());
                    case "soSeri"             -> nz(mw.getSoSeri());
                    case "soLuong"            -> mw.getSoLuong() == null ? "" : String.valueOf(mw.getSoLuong());
                    case "ngayTiepNhan"       -> fmt(mw.getNgayTiepNhan());
                    case "hanSuDungBaoDuong"  -> fmt(mw.getHanSuDungBaoDuong());
                    case "coNong"             -> nz(mw.getCoNong());
                    case "ghiChu"             -> nz(mw.getGhiChu());
                    default                   -> "";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(d))
                .trim();
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String fmt(LocalDate d) { return d == null ? "" : d.toString(); }
    private static String safe(String s) { return s == null ? "" : s; }
}
