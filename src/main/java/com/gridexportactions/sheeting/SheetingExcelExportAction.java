package com.gridexportactions.sheeting;

import com.gridexportactions.view.export.OffsetExcelExporter;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaPropertyPath;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.action.list.ListDataComponentAction;
import io.jmix.flowui.component.ListDataComponent;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.EnhancedDataGrid;
import io.jmix.flowui.data.grid.ContainerDataGridItems;
import io.jmix.flowui.download.DownloadFormat;
import io.jmix.flowui.download.Downloader;
import io.jmix.gridexportflowui.exporter.ExportMode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@ActionType("sheeting_excelExport")
@Component("sheeting_ExcelExportAction")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class SheetingExcelExportAction extends ListDataComponentAction<SheetingExcelExportAction, Object> {

    @Autowired private SheetingConfigService configService;
    @Autowired private Downloader downloader;
    @Autowired private AutowireCapableBeanFactory beanFactory;

    @Value("${app.templates.dir:./app-templates}")
    private String templatesDir;

    public SheetingExcelExportAction() { this("sheeting_excelExport"); }
    public SheetingExcelExportAction(String id) { super(id); }

    @Override public void execute() { actionPerform(null); }

    @Override
    public void actionPerform(com.vaadin.flow.component.Component ignored) {
        var target = getTarget();
        if (!(target instanceof DataGrid<?>)) return;

        @SuppressWarnings("unchecked")
        DataGrid<Object> grid = (DataGrid<Object>) target;

        Predicate<DataGrid.Column<Object>> defaultFilter = DataGrid.Column::isVisible;

        try {
            Optional<SheetingConfigService.Spec> cfgOpt = configService.findFor(grid);
            if (cfgOpt.isPresent()) {
                var spec = cfgOpt.get();

                // Virtual columns (nếu có)
                configService.applyVirtualColumns(grid, spec);

                // Order theo config (DB col -> property path)
                List<String> propertyOrder = configService.resolvePropertyOrder(grid, spec.columns);

                // Nếu có template + (ít nhất một) anchor -> xuất theo POI
                byte[] template = tryLoadTemplateBytes(spec);
                boolean hasAnchors = notBlank(spec.dataAnchor) || notBlank(spec.headerAnchor);
                if (template != null && hasAnchors) {
                    exportViaTemplatePOI(grid, spec, propertyOrder, template);
                    return;
                }

                // Fallback: exporter mặc định
                OffsetExcelExporter exporter = beanFactory.createBean(OffsetExcelExporter.class);
                if (!propertyOrder.isEmpty()) {
                    exporter.withPropertyOrder(propertyOrder);
                    defaultFilter = configService.buildColumnFilter(grid, propertyOrder, false);
                }
                exporter.exportDataGrid(downloader, grid, ExportMode.ALL_ROWS, defaultFilter);
                return;
            }

            // Không có cấu hình
            beanFactory.createBean(OffsetExcelExporter.class)
                    .exportDataGrid(downloader, grid, ExportMode.ALL_ROWS, defaultFilter);

        } catch (Exception ex) {
            // Fallback cuối
            beanFactory.createBean(OffsetExcelExporter.class)
                    .exportDataGrid(downloader, grid, ExportMode.ALL_ROWS, defaultFilter);
        }
    }

    /* ======================== TEMPLATE POI (NO AUTOSIZE) ======================== */

    private void exportViaTemplatePOI(DataGrid<Object> grid,
                                      SheetingConfigService.Spec spec,
                                      List<String> propertyOrder,
                                      byte[] template) throws Exception {

        if (propertyOrder == null || propertyOrder.isEmpty()) {
            propertyOrder = visibleMetaPropertyPaths(grid);
        }
        List<Object> items = collectEntities(grid);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(template));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            // Anchor dữ liệu (case-insensitive, cho phép NamedRange là một vùng -> lấy ô đầu)
            Anchor dataAnchor = resolveAnchor(wb, orElse(spec.dataAnchor, "DATA_START"));
            if (dataAnchor == null) {
                throw new IllegalStateException("Không tìm thấy Named Range cho data: " + spec.dataAnchor);
            }

            // Tính toCol: lấy max giữa lastCellNum của hàng và các merge trên chính hàng đó
            int lastCol = lastColumnIndexOnRow(dataAnchor.sheet, dataAnchor.row);
            if (lastCol < dataAnchor.col) lastCol = dataAnchor.col;

            // Đọc các SEGMENT trên hàng template, BẮT ĐẦU TỪ CỘT ANCHOR
            List<Segment> segments = getSegmentsFromTemplateRow(
                    dataAnchor.sheet, dataAnchor.row, dataAnchor.col, lastCol
            );
            if (segments.isEmpty()) {
                // Không có merge thì dựng segment 1-1 theo số cột cần in
                segments = new ArrayList<>();
                int col = dataAnchor.col;
                for (int i = 0; i < propertyOrder.size(); i++) {
                    segments.add(new Segment(col, col));
                    col++;
                }
            }

            // Ghi dữ liệu: với MỖI segment, lấy style ngay tại (row anchor, fromCol) rồi apply cho dòng mới
            int rowIdx = dataAnchor.row; // ghi ngay tại hàng anchor
            for (Object entity : items) {
                List<String> values = new ArrayList<>(propertyOrder.size());
                for (String path : propertyOrder) {
                    Object v = safeGet(entity, path);
                    values.add(formatVal(v));
                }

                int count = Math.min(values.size(), segments.size());
                for (int i = 0; i < count; i++) {
                    Segment seg = segments.get(i);
                    CellStyle segStyle = styleAt(dataAnchor.sheet, dataAnchor.row, seg.from);
                    writeIntoSegment(dataAnchor.sheet, rowIdx, seg.from, seg.to, values.get(i), segStyle);
                }
                // Nếu còn segment dư thì fill rỗng
                for (int i = count; i < segments.size(); i++) {
                    Segment seg = segments.get(i);
                    CellStyle segStyle = styleAt(dataAnchor.sheet, dataAnchor.row, seg.from);
                    writeIntoSegment(dataAnchor.sheet, rowIdx, seg.from, seg.to, "", segStyle);
                }
                rowIdx++;
            }

            wb.write(bos);
            downloader.download(bos.toByteArray(), buildOutName(spec), DownloadFormat.XLSX);
        }
    }

    /* ------------------ Helpers: read grid, values, formatting ------------------ */

    private static List<Object> collectEntities(DataGrid<Object> grid) {
        if (grid instanceof ListDataComponent) {
            ListDataComponent<?> ldc = (ListDataComponent<?>) grid;
            if (ldc.getItems() instanceof ContainerDataGridItems) {
                ContainerDataGridItems<?> items = (ContainerDataGridItems<?>) ldc.getItems();
                return new ArrayList<>(items.getContainer().getItems());
            }
        }
        return List.of();
    }

    private static List<String> visibleMetaPropertyPaths(DataGrid<Object> grid) {
        @SuppressWarnings("unchecked")
        EnhancedDataGrid<Object> edg = (EnhancedDataGrid<Object>) grid;
        return grid.getAllColumns().stream()
                .filter(DataGrid.Column::isVisible)
                .map(col -> edg.getColumnMetaPropertyPath(col))   // MetaPropertyPath
                .filter(Objects::nonNull)
                .map(MetaPropertyPath::toPathString)
                .collect(Collectors.toList());
    }

    private static Object safeGet(Object entity, String path) {
        try {
            return EntityValues.getValue(entity, path);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String formatVal(Object v) {
        if (v == null) return "";
        if (v instanceof java.time.LocalDate d) return d.toString();
        if (v instanceof java.time.LocalDateTime dt) return dt.toString();
        if (v instanceof java.time.OffsetDateTime odt) return odt.toString();
        if (v instanceof Number n) return n.toString();
        return String.valueOf(v);
    }

    /* ------------------ Helpers: Template + Named Range + Merge ------------------ */

    private static class Anchor {
        final Sheet sheet; final int row; final int col;
        Anchor(Sheet s, int r, int c) { sheet = s; row = r; col = c; }
    }

    /** Tìm NamedRange theo tên (case-insensitive). Nếu NamedRange là một vùng, lấy ô đầu (top-left). */
    private static Anchor resolveAnchor(Workbook wb, String named) {
        if (named == null || named.isBlank()) return null;

        Name found = null;
        try { found = wb.getName(named); } catch (UnsupportedOperationException ignore) {}
        if (found == null) {
            // Tìm case-insensitive
            try {
                for (Name n : wb.getAllNames()) {
                    if (named.equalsIgnoreCase(n.getNameName())) { found = n; break; }
                }
            } catch (UnsupportedOperationException ignore) {}
        }
        if (found == null || found.getRefersToFormula() == null) return null;

        AreaReference ar = new AreaReference(found.getRefersToFormula(), wb.getSpreadsheetVersion());
        CellReference first = ar.getFirstCell();

        Sheet sheet = first.getSheetName() != null
                ? wb.getSheet(first.getSheetName())
                : (found.getSheetIndex() >= 0 ? wb.getSheetAt(found.getSheetIndex()) : wb.getSheetAt(0));

        return new Anchor(sheet, first.getRow(), first.getCol());
    }

    private static class Segment { final int from, to; Segment(int f, int t){from=f;to=t;} }

    /** Trả về danh sách segment theo merge trên hàng `row`, quét từ `fromCol` → `toCol`. */
    private static List<Segment> getSegmentsFromTemplateRow(Sheet sh, int row, int fromCol, int toCol) {
        List<Segment> segs = new ArrayList<>();
        if (toCol < fromCol) return segs;

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

    private static int lastColumnIndexOnRow(Sheet sh, int row) {
        int last = -1;
        Row r = sh.getRow(row);
        if (r != null) last = Math.max(last, r.getLastCellNum() - 1);
        for (CellRangeAddress m : sh.getMergedRegions()) {
            if (m.getFirstRow() <= row && row <= m.getLastRow()) {
                last = Math.max(last, m.getLastColumn());
            }
        }
        return Math.max(last, 0);
    }

    private static CellRangeAddress findMergedRegionStartingAt(Sheet sh, int row, int col) {
        for (CellRangeAddress r : sh.getMergedRegions()) {
            if (r.getFirstRow() == row && r.getFirstColumn() == col) return r;
        }
        return null;
    }

    private static CellRangeAddress findMergedRegionContaining(Sheet sh, int row, int col) {
        for (CellRangeAddress r : sh.getMergedRegions()) {
            if (r.isInRange(row, col)) return r;
        }
        return null;
    }

    private static void writeIntoSegment(Sheet sh, int rowIdx, int from, int to, String val, CellStyle style) {
        Row row = getOrCreateRow(sh, rowIdx);
        for (int c = from; c <= to; c++) {
            Cell cell = getOrCreateCell(row, c);
            if (c == from) cell.setCellValue(val == null ? "" : val);
            else cell.setBlank();
            if (style != null) cell.setCellStyle(style);
        }
        if (to > from && !hasExactMergedRegion(sh, rowIdx, from, to)) {
            sh.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, from, to));
        }
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
        return row != null ? row : sheet.createRow(rowIdx);
    }

    private static Cell getOrCreateCell(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        return cell != null ? cell : row.createCell(colIndex);
    }

    private static CellStyle styleAt(Sheet sh, int row, int col) {
        Row r = sh.getRow(row);
        if (r == null) return null;
        Cell c = r.getCell(col);
        return c != null ? c.getCellStyle() : null;
    }

    /* ------------------ Template + tên file xuất ------------------ */

    private byte[] tryLoadTemplateBytes(SheetingConfigService.Spec spec) {
        try {
            String fileName = firstNonBlank(spec.templateUploaded, defaultFileName(spec.table));
            if (fileName == null) return null;
            Path p = Paths.get(templatesDir, fileName);
            if (!Files.exists(p)) return null;
            return Files.readAllBytes(p);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String buildOutName(SheetingConfigService.Spec spec) {
        String base = (spec.sheetName != null && !spec.sheetName.isBlank())
                ? spec.sheetName : "Export";
        return normalize(base) + ".xlsx";
    }

    private static String defaultFileName(String table) {
        return normalize(table) + "-template.xlsx";
    }

    private static String firstNonBlank(String... opts) {
        for (String s : opts) if (notBlank(s)) return s;
        return null;
    }
    private static String orElse(String s, String def) { return (s == null || s.isBlank()) ? def : s; }
    private static String normalize(String s) {
        if (s == null) return "export";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }
    private static boolean notBlank(String s){ return s != null && !s.isBlank(); }
}
