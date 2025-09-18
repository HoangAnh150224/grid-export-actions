package com.gridexportactions.sheeting;

import com.gridexportactions.view.export.OffsetExcelExporter;
import io.jmix.core.entity.EntityValues;
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
            if (cfgOpt.isEmpty()) {
                // Không có cấu hình -> fallback chuẩn
                fallbackExport(grid, defaultFilter, List.of());
                return;
            }

            var spec = cfgOpt.get();
            // Thứ tự property path (theo DB columns) để fallback export đúng thứ tự
            List<String> propertyOrder = configService.resolvePropertyOrder(grid, spec.columns);

            // Thử export bằng template; nếu không nhận thì fallback
            byte[] template = tryLoadTemplateBytes(spec);
            boolean ok = tryExportViaTemplatePOI(grid, spec, propertyOrder, template);
            if (!ok) {
                fallbackExport(grid, defaultFilter, propertyOrder);
            }
        } catch (Exception ex) {
            // Cứu cháy cuối cùng
            fallbackExport(grid, defaultFilter, List.of());
        }
    }

    /* ======================== TEMPLATE (tplVar & NamedRange) WITH FALLBACK ======================== */

    /**
     * Trả true nếu export thành công bằng template. Nếu template null/hỏng/không map được -> trả false để fallback.
     * Bổ sung: clone nguyên "mẫu" của dòng đầu (style + merges) cho các dòng tiếp theo.
     */
    private boolean tryExportViaTemplatePOI(DataGrid<Object> grid,
                                            SheetingConfigService.Spec spec,
                                            List<String> propertyOrder,
                                            byte[] template) {
        try {
            if (template == null || template.length == 0) return false; // không có template

            // Map DB lower -> property path
            Map<String,String> dbToProperty = configService.buildDbToPropertyPathMap(grid);
            List<Object> items = collectEntities(grid);

            try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(template));
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

                // Chọn sheet
                Sheet sheet = (spec.sheetName != null && !spec.sheetName.isBlank())
                        ? wb.getSheet(spec.sheetName)
                        : null;
                if (sheet == null) sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : wb.createSheet("Export");

                // Resolve anchors nếu có
                Anchor headerA = resolveAnchor(wb, spec.headerAnchor);
                Anchor dataA   = resolveAnchor(wb, spec.dataAnchor);

                if (headerA != null && headerA.sheet != null) sheet = headerA.sheet;
                if (dataA   != null && dataA.sheet   != null) sheet = dataA.sheet;

                int headerRow = headerA != null ? headerA.row : 0;
                int headerCol0 = headerA != null ? headerA.col : 0;

                // =================== ƯU TIÊN: Named Range theo tplVar ===================
                Map<String, Anchor> varAnchors = new LinkedHashMap<>();
                for (String db : spec.columns) {
                    String var = spec.dbToTplVar.getOrDefault(db.toLowerCase(Locale.ROOT), "");
                    if (var == null || var.isBlank()) continue;
                    Anchor a = resolveAnchor(wb, var); // Tên Named Range == tplVar
                    if (a != null) varAnchors.put(var, a);
                }

                Map<String,Integer> varToCol = new LinkedHashMap<>();
                Integer startRowByVars = null;
                if (!varAnchors.isEmpty()) {
                    // Nếu các var nằm sheet khác, ưu tiên sheet của var đầu tiên
                    Anchor first = varAnchors.values().iterator().next();
                    if (first.sheet != null) sheet = first.sheet;

                    // Hàng bắt đầu là hàng nhỏ nhất trong các anchor (thực tế nên trỏ cùng hàng)
                    startRowByVars = varAnchors.values().stream().map(a -> a.row).min(Integer::compareTo).orElse(0);
                    for (Map.Entry<String, Anchor> e : varAnchors.entrySet()) {
                        varToCol.put(e.getKey(), e.getValue().col);
                    }
                }

                // =================== Nếu KHÔNG có Named Range -> dùng header/tuần tự ===================
                if (varToCol.isEmpty()) {
                    if (spec.templateHasHeader) {
                        Row header = getOrCreateRow(sheet, headerRow);
                        Map<String,Integer> headerTextToIndex = scanHeader(header);

                        int nextCol = headerTextToIndex.values().stream().mapToInt(i->i).max().orElse(headerCol0-1) + 1;

                        for (String db : spec.columns) {
                            String var = spec.dbToTplVar.getOrDefault(db.toLowerCase(Locale.ROOT), "");
                            if (var == null || var.isBlank()) continue;
                            Integer idx = headerTextToIndex.get(var);
                            if (idx == null) {
                                // không có cột -> tạo thêm
                                idx = nextCol++;
                                Cell cell = getOrCreateCell(header, idx);
                                cell.setCellValue(var);
                                applyHeaderStyle(sheet.getWorkbook(), cell);
                            }
                            varToCol.put(var, idx);
                        }
                    } else {
                        // Không có header: đổ theo thứ tự columns, bắt đầu từ cột của dataAnchor nếu có, else 0
                        int col = (dataA != null ? dataA.col : 0);
                        for (String db : spec.columns) {
                            String var = spec.dbToTplVar.getOrDefault(db.toLowerCase(Locale.ROOT), "");
                            if (var == null || var.isBlank()) { col++; continue; }
                            varToCol.put(var, col++);
                        }
                    }
                }

                // Không map được gì -> coi như template "không nhận", trả false để fallback
                if (varToCol.isEmpty()) return false;

                // ========== CHUẨN BỊ CLONE DÒNG MẪU ==========
                // Dòng mẫu = dòng đầu tiên sẽ ghi dữ liệu
                int startRow = (startRowByVars != null)
                        ? startRowByVars
                        : (dataA != null ? dataA.row : (spec.templateHasHeader ? headerRow + 1 : 0));

                // thu thập merge trên dòng mẫu (chỉ những merge 1 hàng)
                List<CellRangeAddress> templateRowMerges = mergedRegionsOnRow(sheet, startRow);

                // Số cột tối đa trên dòng mẫu để copy style
                int templateLastCol = Math.max(
                        Optional.ofNullable(sheet.getRow(startRow)).map(Row::getLastCellNum).orElse((short)0) - 1,
                        templateRowMerges.stream().mapToInt(CellRangeAddress::getLastColumn).max().orElse(-1)
                );
                if (templateLastCol < 0) templateLastCol = 0;

                CellStyle dateStyle = buildDateStyle(wb);

                // ========== GHI DỮ LIỆU ==========
                int r = startRow;
                boolean firstRowDone = false;
                for (Object entity : items) {
                    if (!firstRowDone) {
                        // Dòng đầu: dùng sẵn định dạng/merge đang có trong template
                        firstRowDone = true;
                    } else {
                        // Các dòng sau: clone định dạng + merges từ dòng mẫu
                        cloneRowFormatAndMerges(sheet, startRow, r, templateLastCol, templateRowMerges);
                    }

                    Row excelRow = getOrCreateRow(sheet, r);

                    // Ghi từng biến
                    for (String db : spec.columns) {
                        String var = spec.dbToTplVar.get(db.toLowerCase(Locale.ROOT));
                        if (var == null || var.isBlank()) continue;
                        Integer colIdx = varToCol.get(var);
                        if (colIdx == null) continue;

                        String propPath = dbToProperty.get(db.toLowerCase(Locale.ROOT));
                        Object val = getByPath(entity, propPath);

                        Cell cell = getOrCreateCell(excelRow, colIdx);
                        writeValue(cell, val, dateStyle);

                        // Nếu cột này thuộc một merge trên dòng mẫu, đảm bảo set blank cho các ô còn lại của nhóm
                        CellRangeAddress seg = findMergedRegionStartingAt(templateRowMerges, colIdx);
                        if (seg != null && seg.getFirstColumn() < seg.getLastColumn()) {
                            for (int c = seg.getFirstColumn() + 1; c <= seg.getLastColumn(); c++) {
                                getOrCreateCell(excelRow, c).setBlank();
                            }
                        }
                    }

                    r++;
                }

                // Autosize những cột thực sự đã ghi
                for (Integer c : new HashSet<>(varToCol.values())) {
                    try { sheet.autoSizeColumn(c); } catch (Exception ignore) {}
                }

                wb.write(bos);
                downloader.download(bos.toByteArray(),
                        (spec.sheetName == null || spec.sheetName.isBlank() ? "Export" : spec.sheetName) + ".xlsx",
                        DownloadFormat.XLSX);
                return true;
            }
        } catch (Exception e) {
            // bất cứ lỗi gì với template => trả false để fallback
            return false;
        }
    }

    /* ------------------ Fallback: xuất như Grid Export mặc định ------------------ */
    private void fallbackExport(DataGrid<Object> grid,
                                Predicate<DataGrid.Column<Object>> defaultFilter,
                                List<String> propertyOrder) {
        OffsetExcelExporter exporter = beanFactory.createBean(OffsetExcelExporter.class);
        Predicate<DataGrid.Column<Object>> filter = defaultFilter;

        if (propertyOrder != null && !propertyOrder.isEmpty()) {
            exporter.withPropertyOrder(propertyOrder);
            filter = c -> {
                var edg = (EnhancedDataGrid) grid;
                var mpp = edg.getColumnMetaPropertyPath(c);
                return mpp != null && propertyOrder.contains(mpp.toPathString());
            };
        }
        exporter.exportDataGrid(downloader, grid, ExportMode.ALL_ROWS, filter);
    }

    /* ------------------ Helpers: grid & values ------------------ */

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

    private static Object getByPath(Object entity, String path) {
        if (entity == null || path == null || path.isBlank()) return null;
        try { return EntityValues.getValue(entity, path); }
        catch (Exception ignore) { return null; }
    }

    /* ------------------ Helpers: Template + Header + Clone Row ------------------ */

    private static class Anchor { final Sheet sheet; final int row; final int col; Anchor(Sheet s,int r,int c){sheet=s;row=r;col=c;} }

    private static Anchor resolveAnchor(Workbook wb, String name) {
        if (name == null || name.isBlank()) return null;
        try {
            Name nm = wb.getName(name);
            if (nm == null) {
                for (Name n : wb.getAllNames())
                    if (name.equalsIgnoreCase(n.getNameName())) { nm = n; break; }
            }
            if (nm == null || nm.getRefersToFormula() == null) return null;
            AreaReference ar = new AreaReference(nm.getRefersToFormula(), wb.getSpreadsheetVersion());
            CellReference first = ar.getFirstCell();
            Sheet sheet = first.getSheetName() != null ? wb.getSheet(first.getSheetName())
                    : (nm.getSheetIndex() >= 0 ? wb.getSheetAt(nm.getSheetIndex()) : wb.getSheetAt(0));
            return new Anchor(sheet, first.getRow(), first.getCol());
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String,Integer> scanHeader(Row header) {
        Map<String,Integer> map = new HashMap<>();
        if (header == null) return map;
        short last = header.getLastCellNum();
        if (last < 0) last = 0;
        for (int i = 0; i < last + 256; i++) {
            Cell c = header.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (c == null) continue;
            String txt = getString(c).trim();
            if (!txt.isEmpty()) map.put(txt, i);
        }
        return map;
    }

    private static Row getOrCreateRow(Sheet s, int row) {
        Row r = s.getRow(row);
        return r != null ? r : s.createRow(row);
    }
    private static Cell getOrCreateCell(Row r, int col) {
        Cell c = r.getCell(col);
        return c != null ? c : r.createCell(col);
    }

    private static void applyHeaderStyle(Workbook wb, Cell cell) {
        Font bold = wb.createFont(); bold.setBold(true);
        CellStyle st = wb.createCellStyle(); st.setFont(bold);
        cell.setCellStyle(st);
    }

    private static String getString(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> String.valueOf(c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }

    private static void writeValue(Cell cell, Object val, CellStyle dateStyle) {
        if (val == null) { cell.setBlank(); return; }
        if (val instanceof Number n) { cell.setCellValue(n.doubleValue()); return; }
        if (val instanceof java.util.Date d) { cell.setCellValue(d); cell.setCellStyle(dateStyle); return; }
        if (val instanceof java.sql.Date d) { cell.setCellValue(new java.util.Date(d.getTime())); cell.setCellStyle(dateStyle); return; }
        if (val instanceof java.sql.Timestamp ts) { cell.setCellValue(new java.util.Date(ts.getTime())); cell.setCellStyle(dateStyle); return; }
        if (val instanceof Boolean b) { cell.setCellValue(b); return; }
        cell.setCellValue(String.valueOf(val));
    }

    private static CellStyle buildDateStyle(Workbook wb) {
        CreationHelper helper = wb.getCreationHelper();
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
        return style;
    }

    /* ---------- Clone row: copy style + merges (không copy giá trị text/số) ---------- */

    /** Lấy các merged region 1 hàng nằm trên đúng row. */
    private static List<CellRangeAddress> mergedRegionsOnRow(Sheet sh, int row) {
        List<CellRangeAddress> out = new ArrayList<>();
        for (CellRangeAddress r : sh.getMergedRegions()) {
            if (r.getFirstRow() == row && r.getLastRow() == row) out.add(r);
        }
        return out;
    }

    private static CellRangeAddress findMergedRegionStartingAt(List<CellRangeAddress> list, int col) {
        for (CellRangeAddress r : list) {
            if (r.getFirstColumn() == col) return r;
        }
        return null;
    }

    /** Clone định dạng + merges từ templateRow -> targetRow. Không copy giá trị. */
    private static void cloneRowFormatAndMerges(Sheet sheet,
                                                int templateRow,
                                                int targetRow,
                                                int templateLastCol,
                                                List<CellRangeAddress> templateMerges) {
        // 1) Copy row height
        Row src = sheet.getRow(templateRow);
        Row dst = getOrCreateRow(sheet, targetRow);
        if (src != null) dst.setHeight(src.getHeight());

        // 2) Copy cell styles
        for (int c = 0; c <= templateLastCol; c++) {
            Cell srcCell = (src == null) ? null : src.getCell(c);
            Cell dstCell = getOrCreateCell(dst, c);
            if (srcCell != null) {
                CellStyle st = srcCell.getCellStyle();
                if (st != null) dstCell.setCellStyle(st);
            } else {
                // nếu template không có cell, để trống
                dstCell.setBlank();
            }
        }

        // 3) Recreate merged regions for this row (avoid duplicates)
        for (CellRangeAddress r : templateMerges) {
            CellRangeAddress copy = new CellRangeAddress(targetRow, targetRow, r.getFirstColumn(), r.getLastColumn());
            if (!hasExactMergedRegion(sheet, copy)) {
                sheet.addMergedRegion(copy);
            }
        }
    }

    private static boolean hasExactMergedRegion(Sheet sh, CellRangeAddress region) {
        for (CellRangeAddress r : sh.getMergedRegions()) {
            if (r.getFirstRow() == region.getFirstRow()
                    && r.getLastRow() == region.getLastRow()
                    && r.getFirstColumn() == region.getFirstColumn()
                    && r.getLastColumn() == region.getLastColumn()) return true;
        }
        return false;
    }

    /* ------------------ Template bytes ------------------ */

    private byte[] tryLoadTemplateBytes(SheetingConfigService.Spec spec) {
        try {
            if (spec.templateUploaded == null || spec.templateUploaded.isBlank()) return null;
            Path p = Paths.get(templatesDir, spec.templateUploaded);
            if (!Files.exists(p)) return null;
            return Files.readAllBytes(p);
        } catch (Exception ignore) {
            return null;
        }
    }
}
