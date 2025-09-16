package com.gridexportactions.view.export;

import com.vaadin.flow.component.grid.Grid;
import io.jmix.core.DateTimeTransformations;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.download.ByteArrayDownloadDataProvider;
import io.jmix.flowui.download.Downloader;
import io.jmix.gridexportflowui.GridExportProperties;
import io.jmix.gridexportflowui.exporter.ExportMode;
import io.jmix.gridexportflowui.exporter.entitiesloader.AllEntitiesLoader;
import io.jmix.gridexportflowui.exporter.entitiesloader.AllEntitiesLoaderFactory;
import io.jmix.gridexportflowui.exporter.excel.ExcelAutoColumnSizer;
import io.jmix.gridexportflowui.exporter.excel.ExcelExporter;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.jmix.flowui.download.DownloadFormat.XLSX;

/** Exporter: nếu có template + Named Range thì đổ đúng vị trí; nếu không thì dùng Jmix mặc định. */
public class OffsetExcelExporter extends ExcelExporter {

    // Template + anchors
    private byte[] templateBytes;
    private String headerAnchorName;      // ví dụ: HEADER_START
    private String dataAnchorName;        // ví dụ: DATA_START
    private boolean templateHasHeader;    // true => KHÔNG ghi header

    // Offsets nội bộ (không expose ra API)
    private Integer headerRowOffset, headerColOffset;
    private Integer dataRowOffset,   dataColOffset;

    // Optional: ép thứ tự cột theo property path
    private List<String> propertyOrder = List.of();

    public OffsetExcelExporter(GridExportProperties gridExportProperties,
                               Notifications notifications,
                               AllEntitiesLoaderFactory allEntitiesLoaderFactory,
                               CurrentAuthentication currentAuthentication,
                               DateTimeTransformations dateTimeTransformations) {
        super(gridExportProperties, notifications, allEntitiesLoaderFactory, currentAuthentication, dateTimeTransformations);
    }

    /* ========== Fluent API ========== */

    public OffsetExcelExporter withPropertyOrder(List<String> order) {
        this.propertyOrder = order == null ? List.of() : List.copyOf(order);
        return this;
    }

    /** Dùng template + 2 anchor độc lập. Nếu không có template, exporter sẽ rơi về mặc định. */
    public OffsetExcelExporter withTemplate(byte[] templateBytes,
                                            String headerAnchor,
                                            String dataAnchor,
                                            boolean templateHasHeader) {
        this.templateBytes = (templateBytes == null || templateBytes.length == 0) ? null : templateBytes;
        this.headerAnchorName = blankToNull(headerAnchor);
        this.dataAnchorName   = blankToNull(dataAnchor);
        this.templateHasHeader = templateHasHeader;
        return this;
    }

    /* ========== Core ========== */

    @Override
    public void exportDataGrid(Downloader downloader,
                               Grid<Object> dataGrid,
                               ExportMode exportMode,
                               Predicate<Grid.Column<Object>> columnFilter) {
        // Không có template/anchor => xài nguyên Jmix
        if (templateBytes == null && headerAnchorName == null && dataAnchorName == null) {
            super.exportDataGrid(downloader, dataGrid, exportMode, columnFilter);
            return;
        }

        // Có template hoặc có anchor => mở template và tính tọa độ
        try {
            createWorkbookWithTemplate();
            createFonts();
            createFormats();

            List<Grid.Column<Object>> columns = getColumns(dataGrid, columnFilter);
            createAutoColumnSizers(columns.size());

            boolean writeHeader = !templateHasHeader;
            if (writeHeader) writeHeaderRowAt(columns, headerRowOffsetOrData());

            if (!(dataGrid instanceof io.jmix.flowui.component.ListDataComponent<?> ldc)
                    || !(ldc.getItems() instanceof io.jmix.flowui.data.grid.ContainerDataGridItems<?> items)) {
                throw new IllegalStateException("DataGrid is not bound to data");
            }

            int dataRow0 = dataRowOffsetOrBelowHeader(writeHeader);
            if (exportMode == ExportMode.SELECTED_ROWS && !dataGrid.getSelectedItems().isEmpty()) {
                Set<Object> selected = dataGrid.getSelectedItems();
                List<Object> ordered = items.getContainer().getItems().stream()
                        .filter(selected::contains).collect(Collectors.toList());
                for (int i = 0; i < ordered.size(); i++) {
                    writeDataRow(dataGrid, columns, dataRow0 + i, ordered.get(i));
                }
            } else if (exportMode == ExportMode.CURRENT_PAGE) {
                int r = 0;
                for (Object item : items.getContainer().getItems()) {
                    writeDataRow(dataGrid, columns, dataRow0 + r++, item);
                }
            } else {
                AllEntitiesLoader loader = allEntitiesLoaderFactory.getEntitiesLoader();
                loader.loadAll(((io.jmix.flowui.component.ListDataComponent<?>) dataGrid).getItems(), ctx -> {
                    writeDataRow(dataGrid, columns, dataRow0 + ctx.getEntityNumber(), ctx.getEntity());
                    return true;
                });
            }

            // auto width cho các cột tại vị trí dataCol0
            int col0 = dataColOffsetOrZero();
            for (int c = 0; c < columns.size(); c++) {
                if (sizers != null && c < sizers.length && sizers[c] != null) {
                    sheet.setColumnWidth(col0 + c, sizers[c].getWidth() * COL_WIDTH_MAGIC);
                }
            }

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                if (isXlsxMaxRowNumberExceeded()) showWarnNotification();
                var dp = new ByteArrayDownloadDataProvider(
                        out.toByteArray(),
                        uiProperties.getSaveExportedByteArrayDataThresholdBytes(),
                        coreProperties.getTempDir()
                );
                downloader.download(dp, getFileName(dataGrid) + "." + XLSX.getFileExt(), XLSX);
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to write document", e);
        } finally {
            disposeWorkBook();
        }
    }

    /** Mặc định dùng Jmix columns, nhưng có thể reorder theo propertyOrder nếu bạn set. */
    @Override
    protected List<Grid.Column<Object>> getColumns(Grid<Object> dataGrid,
                                                   Predicate<Grid.Column<Object>> columnFilter) {
        List<Grid.Column<Object>> cols = super.getColumns(dataGrid, columnFilter);
        if (propertyOrder == null || propertyOrder.isEmpty()) return cols;

        var edg = (io.jmix.flowui.component.grid.EnhancedDataGrid) dataGrid;
        Map<String, Grid.Column<Object>> byProp = new LinkedHashMap<>();
        for (var c : cols) {
            var mpp = edg.getColumnMetaPropertyPath(c);
            if (mpp != null) byProp.put(mpp.toPathString(), c);
        }
        List<Grid.Column<Object>> ordered = new ArrayList<>();
        for (String p : propertyOrder) {
            var c = byProp.get(p);
            if (c != null) ordered.add(c);
        }
        for (var c : cols) if (!ordered.contains(c)) ordered.add(c);
        return ordered;
    }

    /* ========== Template helpers ========== */

    private void createWorkbookWithTemplate() throws IOException {
        if (templateBytes == null) {
            // không có template nhưng có anchor (thật kỳ quặc) -> workbook mới
            wb = new XSSFWorkbook();
            sheet = wb.createSheet("Export");
            return;
        }
        wb = new XSSFWorkbook(new ByteArrayInputStream(templateBytes));

        boolean resolved = false;
        if (dataAnchorName != null) resolved |= resolveAnchor(dataAnchorName, false);
        if (headerAnchorName != null) resolved |= resolveAnchor(headerAnchorName, true);
        if (!resolved) {
            // không có name nào hợp lệ -> fallback sheet đầu
            sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : wb.createSheet("Export");
        }
    }

    /** resolve anchor; isHeader=true -> cập nhật header offsets, ngược lại cập nhật data offsets */
    private boolean resolveAnchor(String name, boolean isHeader) {
        Name nm = wb.getName(name);
        if (nm == null) return false;
        AreaReference area;
        try { area = new AreaReference(nm.getRefersToFormula(), SpreadsheetVersion.EXCEL2007); }
        catch (IllegalArgumentException ex) { return false; }
        CellReference first = area.getFirstCell();
        if (first == null) return false;
        Sheet s = wb.getSheet(first.getSheetName());
        if (s == null) return false;

        sheet = s; // set sheet theo anchor
        if (isHeader) {
            headerRowOffset = first.getRow();
            headerColOffset = (int) first.getCol();
        } else {
            dataRowOffset = first.getRow();
            dataColOffset = (int) first.getCol();
        }
        return true;
    }

    private int headerRowOffsetOrData() {
        if (headerRowOffset != null) return headerRowOffset;
        if (dataRowOffset != null)   return dataRowOffset;
        return 0;
    }
    private int headerColOffsetOrData() {
        if (headerColOffset != null) return headerColOffset;
        if (dataColOffset != null)   return dataColOffset;
        return 0;
    }
    private int dataRowOffsetOrBelowHeader(boolean headerWritten) {
        if (dataRowOffset != null) return dataRowOffset;
        // nếu không có dataAnchor: data bắt đầu ngay dưới header đã ghi, hoặc tại vị trí header nếu templateHasHeader=true
        int base = headerRowOffsetOrData();
        return base + (headerWritten ? 1 : 0);
    }
    private int dataColOffsetOrZero() {
        if (dataColOffset != null) return dataColOffset;
        return headerColOffsetOrData();
    }

    private void writeHeaderRowAt(List<Grid.Column<Object>> columns, int row0) {
        int col0 = headerColOffsetOrData();

        Row headerRow = sheet.getRow(row0);
        if (headerRow == null) headerRow = sheet.createRow(row0);

        float maxHeight = sheet.getDefaultRowHeightInPoints();
        CellStyle headerCellStyle = wb.createCellStyle();
        headerCellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        for (Grid.Column<Object> column : columns) {
            String headerText = getColumnHeaderText(column);
            int cnt = org.apache.commons.lang3.StringUtils.countMatches(headerText, "\n");
            if (cnt > 0) {
                maxHeight = Math.max(maxHeight, (cnt + 1) * sheet.getDefaultRowHeightInPoints());
                headerCellStyle.setWrapText(true);
            }
        }
        headerRow.setHeightInPoints(maxHeight);

        createAutoColumnSizers(columns.size()); // ensure exists
        for (int c = 0; c < columns.size(); c++) {
            String headerText = getColumnHeaderText(columns.get(c));
            Cell cell = headerRow.getCell(col0 + c);
            if (cell == null) cell = headerRow.createCell(col0 + c);

            if (sizers != null && c < sizers.length && sizers[c] == null) sizers[c] = new ExcelAutoColumnSizer();
            if (sizers != null && c < sizers.length) sizers[c].notifyCellValue(headerText, boldFont);

            var rich = new XSSFRichTextString(headerText);
            rich.applyFont(boldFont);
            cell.setCellValue(rich);
            cell.setCellStyle(headerCellStyle);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void writeDataRow(Grid<?> dataGrid,
                              List<Grid.Column<Object>> columns,
                              int targetRowIndex,
                              Object entityInstance) {
        Row row = sheet.getRow(targetRowIndex);
        if (row == null) row = sheet.createRow(targetRowIndex);

        int col0 = dataColOffsetOrZero();
        int level = 0;
        boolean addLevelPadding = dataGrid instanceof io.jmix.flowui.component.grid.TreeDataGrid;
        if (addLevelPadding) {
            var provider = ((io.jmix.flowui.component.grid.TreeDataGrid<Object>) dataGrid).getDataProvider();
            level = ((io.jmix.flowui.data.grid.ContainerTreeDataGridItems) provider).getLevel(entityInstance);
        }
        var edg = (io.jmix.flowui.component.grid.EnhancedDataGrid) dataGrid;

        for (int c = 0; c < columns.size(); c++) {
            Cell cell = row.getCell(col0 + c);
            if (cell == null) cell = row.createCell(col0 + c);

            Grid.Column<Object> col = columns.get(c);
            var propertyPath = edg.getColumnMetaPropertyPath(col);

            Object cellValue = getColumnValue(dataGrid, col, entityInstance);
            formatValueCell(cell, cellValue, propertyPath, c, targetRowIndex, level, null);
        }
    }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
}
