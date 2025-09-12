package com.gridexportactions.view.militarywarehouse;

import com.gridexportactions.entity.MilitaryWarehouse;
import com.gridexportactions.view.main.MainView;
import com.vaadin.flow.component.ClickEvent;
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
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.View;
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

    // Named range: 1 hàng mẫu (trên sheet) để bắt đầu ghi DATA
    @Value("${app.templates.militaryWarehouse.tableAnchorName:tableValue}")
    private String tableAnchorName;

    // Kế hoạch span cho từng cột
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

    // Ước lượng số ký tự/ô để auto-span tiêu đề
    private static final int CHARS_PER_CELL = 8;

    // ===== Lifecycle =====
    @Subscribe
    public void onInit(View.InitEvent e) {
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

    private static void clearMergedRegionsInRow(Sheet sh, int rowIdx, int from, int to) {
        for (int i = sh.getNumMergedRegions() - 1; i >= 0; i--) {
            CellRangeAddress r = sh.getMergedRegion(i);
            if (r.getFirstRow() <= rowIdx && r.getLastRow() >= rowIdx) {
                if (!(r.getLastColumn() < from || r.getFirstColumn() > to)) {
                    sh.removeMergedRegion(i);
                }
            }
        }
    }

    // ---- Helpers cho style/ghi dữ liệu vào segment ----

    // Đọc style của 1 dải ô trên một hàng mẫu, trả về list style theo từng cột
    private static List<CellStyle> readStylesFromTemplateRow(Sheet sheet, int rowIdx, int fromCol, int toCol) {
        Row row = getOrCreateRow(sheet, rowIdx);
        List<CellStyle> styles = new ArrayList<>();
        for (int c = fromCol; c <= toCol; c++) {
            Cell cell = row.getCell(c);
            if (cell == null) {
                cell = row.createCell(c);
            }
            styles.add(cell.getCellStyle());
        }
        return styles;
    }

    // Kéo dài danh sách style theo số lượng segment cần dùng (lặp lại cái cuối)
    private static List<List<CellStyle>> extendStylesIfNeeded(List<List<CellStyle>> base, int targetSize) {
        List<List<CellStyle>> out = new ArrayList<>(base);
        if (out.isEmpty()) return out;
        while (out.size() < targetSize) {
            out.add(out.get(out.size() - 1));
        }
        return out;
    }

    // Ghi text và apply style vào một segment, tự merge nếu span > 1
    private static void writeIntoSegmentUsingStyles(Sheet sheet, int rowIdx, int fromCol, int toCol,
                                                    String text, List<CellStyle> providedStyles) {
        Row row = getOrCreateRow(sheet, rowIdx);
        int width = Math.max(1, toCol - fromCol + 1);

        // Nếu số style ít hơn số cột, lặp lại style cuối
        List<CellStyle> styles = new ArrayList<>();
        if (providedStyles == null || providedStyles.isEmpty()) {
            CellStyle def = sheet.getWorkbook().getCellStyleAt((short) 0);
            for (int i = 0; i < width; i++) styles.add(def);
        } else {
            for (int i = 0; i < width; i++) {
                styles.add(i < providedStyles.size()
                        ? providedStyles.get(i)
                        : providedStyles.get(providedStyles.size() - 1));
            }
        }

        // Tạo ô, set style, chỉ cell đầu chứa text
        for (int offset = 0; offset < width; offset++) {
            int c = fromCol + offset;
            Cell cell = row.getCell(c);
            if (cell == null) cell = row.createCell(c);
            cell.setCellStyle(styles.get(offset));
            if (offset == 0) {
                cell.setCellValue(text == null ? "" : text);
            } else {
                cell.setBlank();
            }
        }

        // Merge đúng span nếu cần
        if (width > 1 && !hasExactMergedRegion(sheet, rowIdx, fromCol, toCol)) {
            sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, fromCol, toCol));
        }
    }

    // ===== UI: Gộp cột động trong GRID (không ảnh hưởng export) =====
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

    // ===== EXPORT: In TIÊU ĐỀ (ta.row-1) và DỮ LIỆU (từ ta.row) =====
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

            // 1) Lấy hàng mẫu (tableValue)
            TableArea ta = resolveTableArea(wb, tableAnchorName);
            if (ta == null) {
                notifications.create("Không tìm thấy Named Range: " + tableAnchorName)
                        .withType(Notifications.Type.ERROR).show();
                return;
            }
            Sheet sheet = ta.sheet;

            List<Segment> templateSegments = getSegmentsFromTemplateRow(sheet, ta.row, ta.fromCol, ta.toCol);
            if (templateSegments.isEmpty()) {
                notifications.create("Hàng mẫu (" + tableAnchorName + ") không có cột nào.")
                        .withType(Notifications.Type.ERROR).show();
                return;
            }

            // 2) Cột đang hiển thị + cột STT
            List<Grid.Column<MilitaryWarehouse>> visibleCols = getVisibleColumnsInUiOrder();
            boolean leadingStt = true;

            // 3) Tiêu đề theo UI
            List<String> headerTexts = new ArrayList<>();
            if (leadingStt) headerTexts.add("STT");
            for (Grid.Column<MilitaryWarehouse> c : visibleCols) headerTexts.add(headerTextFromUI(c));

            // 4) Tính span mục tiêu theo tiêu đề (KHÔNG cộng padding)
            List<Integer> targetSpans = computeTargetSpans(visibleCols, headerTexts, templateSegments, leadingStt);

            // 5) Dựng segments liên tục từ ta.fromCol
            List<Segment> allSegments = buildSegmentsFromSpans(ta.fromCol, targetSpans);
            int lastTo = allSegments.get(allSegments.size() - 1).to;

            // 6) Lấy style: header ở ta.row-1, body ở ta.row; thiếu thì lặp style cuối
            int headerRow = Math.max(0, ta.row - 1);
            List<List<CellStyle>> headerBase = new ArrayList<>();
            List<List<CellStyle>> bodyBase   = new ArrayList<>();
            for (Segment s : templateSegments) {
                headerBase.add(readStylesFromTemplateRow(sheet, headerRow, s.from, s.to));
                bodyBase.add(readStylesFromTemplateRow(sheet, ta.row,    s.from, s.to));
            }
            List<List<CellStyle>> headerStyles = extendStylesIfNeeded(headerBase, allSegments.size());
            List<List<CellStyle>> bodyStyles   = extendStylesIfNeeded(bodyBase,   allSegments.size());

            // 7) Clear merge cũ trên dòng tiêu đề và dòng data mẫu, để merge lại cho khớp
            clearMergedRegionsInRow(sheet, headerRow, ta.fromCol, lastTo);
            clearMergedRegionsInRow(sheet, ta.row,    ta.fromCol, lastTo);

            // 8) In TIÊU ĐỀ
            for (int i = 0; i < allSegments.size(); i++) {
                Segment seg = allSegments.get(i);
                String text = (i < headerTexts.size()) ? headerTexts.get(i) : "";
                writeIntoSegmentUsingStyles(sheet, headerRow, seg.from, seg.to, text, headerStyles.get(i));
            }

            // 9) Lấy dữ liệu + sort theo Grid
            List<MilitaryWarehouse> all = dataManager.load(MilitaryWarehouse.class)
                    .query("select e from MilitaryWarehouse e")
                    .fetchPlan("_base")
                    .list();
            Comparator<MilitaryWarehouse> cmp = buildComparatorFromGridSort(militaryWarehousesDataGrid.getSortOrder());
            if (cmp != null) all.sort(cmp);

            // 10) In PHẦN THÂN
            int rowIdx = ta.row;
            int stt = 1;
            for (MilitaryWarehouse mw : all) {
                List<String> values = new ArrayList<>();
                if (leadingStt) values.add(String.valueOf(stt));
                for (Grid.Column<MilitaryWarehouse> col : visibleCols) values.add(valueFor(mw, col));

                for (int i = 0; i < allSegments.size(); i++) {
                    Segment seg = allSegments.get(i);
                    String v = (i < values.size()) ? values.get(i) : "";
                    writeIntoSegmentUsingStyles(sheet, rowIdx, seg.from, seg.to, v, bodyStyles.get(i));
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

    // ------- Helper cho export (span, header, style, merge) -------
    private List<Integer> spansForVisibleCols(List<Grid.Column<MilitaryWarehouse>> visibleCols) {
        List<Integer> spans = new ArrayList<>();
        for (Grid.Column<MilitaryWarehouse> c : visibleCols) {
            String key = Objects.toString(c.getKey(), "");
            spans.add(Math.max(1, colSpanPlan.getOrDefault(key, 3)));
        }
        return spans;
    }

    private List<Integer> computeTargetSpans(List<Grid.Column<MilitaryWarehouse>> visibleCols,
                                             List<String> headerTexts,
                                             List<Segment> templateSegments,
                                             boolean leadingStt) {
        List<Integer> uiPlanSpans = spansForVisibleCols(visibleCols);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < headerTexts.size(); i++) {
            int base = (i < templateSegments.size())
                    ? (templateSegments.get(i).to - templateSegments.get(i).from + 1)
                    : 1;
            int plan = 1;
            if (!(leadingStt && i == 0)) {
                int uiIdx = leadingStt ? i - 1 : i;
                if (uiIdx >= 0 && uiIdx < uiPlanSpans.size())
                    plan = Math.max(plan, uiPlanSpans.get(uiIdx));
            }
            String h = headerTexts.get(i) == null ? "" : headerTexts.get(i);
            int byText = Math.max(1, (int) Math.ceil(h.length() / (double) CHARS_PER_CELL));
            int span = Math.max(base, Math.max(plan, byText));
            // KHÔNG cộng padding 1 ô nữa
            out.add(Math.max(1, span));
        }
        return out;
    }

    private List<Segment> buildSegmentsFromSpans(int startCol, List<Integer> spans) {
        List<Segment> segs = new ArrayList<>();
        int col = startCol;
        for (int sp : spans) {
            int from = col;
            int to = col + Math.max(1, sp) - 1;
            segs.add(new Segment(from, to));
            col = to + 1;
        }
        return segs;
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
            if (comp instanceof com.vaadin.flow.component.HasText ht) {
                String s = ht.getText();
                if (s != null && !s.isBlank()) return s;
            }
        } catch (Exception ignored) {}
        String k = col.getKey();
        return k == null ? "" : k;
    }

    // ===== Value mapping =====
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
