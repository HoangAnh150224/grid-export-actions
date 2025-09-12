package com.gridexportactions.view.sheeting;

import com.gridexportactions.entity.SheetingConfig;
import com.gridexportactions.view.main.MainView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Label;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.twincolumn.TwinColumn;
import io.jmix.flowui.download.Downloader;
import io.jmix.flowui.download.DownloadFormat;
import io.jmix.flowui.model.KeyValueCollectionContainer;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

// Apache POI
// LƯU Ý: KHÔNG import InvalidFormatException vì create(InputStream) không ném nó ở bản POI hiện tại
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Route(value = "sheeting-view", layout = MainView.class)
@ViewController("SheetingView")
@ViewDescriptor("sheeting-view.xml")
public class SheetingView extends StandardView {

    @Autowired @Qualifier("dataSource")
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Notifications notifications;

    // lưu/tải template
    @Autowired
    private FileStorage fileStorage;

    @Autowired
    private Downloader downloader;

    @ViewComponent("tablesDc") private KeyValueCollectionContainer tablesDc;
    @ViewComponent("fieldsDc") private KeyValueCollectionContainer fieldsDc;

    @ViewComponent private ComboBox<KeyValueEntity> tablesCb;
    @ViewComponent private TwinColumn<KeyValueEntity> colsTwin;
    @ViewComponent private Label tableInfo;
    @ViewComponent private Label jsonPreview;

    // UI template
    @ViewComponent private Upload templateUpload;
    @ViewComponent private Button downloadTemplateBtn;
    @ViewComponent private Label templateInfo;

    private List<KeyValueEntity> allTables = new ArrayList<>();
    private String currentTableFqn;
    private String currentSelectionJson = "";

    // buffer upload
    private MemoryBuffer templateBuffer;

    @Subscribe
    public void onInit(InitEvent event) {
        // jsonPreview ẩn hoàn toàn, vẫn cập nhật text nội bộ
        jsonPreview.setVisible(false);

        tableInfo.getElement().getStyle().set("font-size", "12px");
        tableInfo.getElement().getStyle().set("color", "var(--lumo-secondary-text-color)");

        tablesCb.setItemLabelGenerator(this::fqn);
        colsTwin.setItemLabelGenerator(kv -> Objects.toString(kv.getValue("name"), ""));

        tablesCb.addValueChangeListener(e -> {
            KeyValueEntity sel = e.getValue();
            if (sel == null) {
                currentTableFqn = null;
                tableInfo.setText("");
                fieldsDc.setItems(Collections.emptyList());
                colsTwin.clear();
                updateJsonState();
                refreshTemplateUiForCurrentTable();
            } else {
                currentTableFqn = fqn(sel);
                tableInfo.setText(quickInfo(sel));
                loadColumnsForTable(sel);
                boolean applied = applySavedSelection(); // cố gắng nạp lại
                if (!applied) {
                    colsTwin.clear(); // không có cấu hình thì reset rỗng
                }
                updateJsonState();
                refreshTemplateUiForCurrentTable();
            }
        });

        colsTwin.addValueChangeListener(e -> updateJsonState());

        // cấu hình upload template
        templateBuffer = new MemoryBuffer();
        templateUpload.setReceiver(templateBuffer);
        templateUpload.addSucceededListener(s -> onTemplateUploadSucceeded(s.getFileName()));
        templateUpload.addFileRejectedListener(r ->
                notifications.create(r.getErrorMessage()).withType(Notifications.Type.WARNING).show());
        templateUpload.addFailedListener(f ->
                notifications.create("Upload thất bại: " + (f.getReason() != null ? f.getReason().getMessage() : "unknown"))
                        .withType(Notifications.Type.ERROR).show());

        // nút tải template hiện có
        downloadTemplateBtn.addClickListener(this::onDownloadTemplateClicked);

        reloadTables();
    }

    @SuppressWarnings("unchecked")
    private boolean applySavedSelection() {
        if (currentTableFqn == null || currentTableFqn.isBlank()) return false;

        Optional<SheetingConfig> opt = dataManager.load(SheetingConfig.class)
                .query("select e from SheetingConfig e where e.tableName = :t")
                .parameter("t", currentTableFqn)
                .optional();

        if (opt.isEmpty()) return false;

        try {
            Map<String, Object> json = objectMapper.readValue(opt.get().getColumnsJson(), Map.class);
            Object cols = json.get("columns");
            if (!(cols instanceof Collection<?> colList)) return false;

            Set<String> names = colList.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // match theo name trong fieldsDc
            Collection<KeyValueEntity> all = fieldsDc.getItems();
            if (all == null || all.isEmpty()) return false;

            LinkedHashSet<KeyValueEntity> selected = all.stream()
                    .filter(kv -> names.contains(Objects.toString(kv.getValue("name"), "")))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            colsTwin.setValue(selected);   // set vào TwinColumn
            return !selected.isEmpty();
        } catch (Exception ex) {
            // JSON lỗi thì thôi, coi như chưa có cấu hình hợp lệ
            return false;
        }
    }

    @Subscribe("exportBtn")
    public void onExportBtnClick(ClickEvent<Button> event) {
        if (currentTableFqn == null || currentTableFqn.isBlank()) {
            notifications.create("Chưa chọn bảng").withType(Notifications.Type.WARNING).show();
            return;
        }
        // lấy danh sách cột đã chọn
        List<String> columns = colsTwin.getValue() == null ? Collections.emptyList()
                : colsTwin.getValue().stream()
                .map(kv -> Objects.toString(kv.getValue("name"), ""))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

        if (columns.isEmpty()) {
            notifications.create("Chưa chọn cột nào để xuất").withType(Notifications.Type.WARNING).show();
            return;
        }

        // xuất dữ liệu
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String[] st = splitSchemaAndTable(currentTableFqn);
            String schema = st[0];
            String table  = st[1];

            String sql = buildSelectSql(md, schema, table, columns);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                // lấy workbook: ưu tiên template nếu có
                Workbook wb = createWorkbookFromTemplateIfAny();
                Sheet sheet = resolveTargetSheet(wb);

                int[] start = resolveStartPositionFromNamedRange(wb, "DATA"); // [row, col]
                int startRow = start[0];
                int startCol = start[1];

                // ghi header
                Row header = getOrCreateRow(sheet, startRow);
                for (int c = 0; c < columns.size(); c++) {
                    Cell cell = getOrCreateCell(header, startCol + c);
                    cell.setCellValue(columns.get(c));
                    CellStyle style = wb.createCellStyle();
                    Font font = wb.createFont();
                    font.setBold(true);
                    style.setFont(font);
                    cell.setCellStyle(style);
                }

                // ghi dữ liệu
                int rowIdx = startRow + 1;
                DataFormatter formatter = new DataFormatter(Locale.getDefault());

                while (rs.next()) {
                    Row row = getOrCreateRow(sheet, rowIdx++);
                    for (int c = 0; c < columns.size(); c++) {
                        Object val = rs.getObject(c + 1); // thứ tự theo SELECT
                        Cell cell = getOrCreateCell(row, startCol + c);
                        writeCellValue(cell, val, wb, formatter);
                    }
                }

                // auto-size cho các cột mới
                for (int c = 0; c < columns.size(); c++) {
                    try { sheet.autoSizeColumn(startCol + c); } catch (Exception ignore) {}
                }

                // xuống file và tải
                String fileName = (currentTableFqn.replace('.', '_')) + ".xlsx";
                try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                    wb.write(baos);
                    wb.close();
                    downloader.download(baos.toByteArray(), fileName, DownloadFormat.XLSX);
                }
            }
        } catch (Exception ex) {
            notifications.create("Lỗi xuất Excel: " + ex.getMessage())
                    .withType(Notifications.Type.ERROR).show();
        }
    }

    /* ===================== NÚT LƯU / HỦY ===================== */

    @Subscribe("saveBtn")
    public void onSaveBtnClick(ClickEvent<Button> event) {
        if (currentTableFqn == null || currentTableFqn.isBlank()) {
            notifications.create("Chưa chọn bảng").withType(Notifications.Type.WARNING).show();
            return;
        }
        if (currentSelectionJson == null || currentSelectionJson.isBlank()) {
            updateJsonState();
        }

        Optional<SheetingConfig> opt = dataManager.load(SheetingConfig.class)
                .query("select e from SheetingConfig e where e.tableName = :t")
                .parameter("t", currentTableFqn)
                .optional();

        SheetingConfig cfg = opt.orElseGet(() -> dataManager.create(SheetingConfig.class));
        cfg.setTableName(currentTableFqn);
        cfg.setColumnsJson(currentSelectionJson);

        dataManager.save(cfg);
        notifications.create("Đã lưu cấu hình cho " + currentTableFqn)
                .withType(Notifications.Type.SUCCESS).show();
    }

    @Subscribe("cancelBtn")
    public void onCancelBtnClick(ClickEvent<Button> event) {
        // hủy thay đổi hiện tại trên UI, không đụng DB
        colsTwin.clear();
        updateJsonState();
        notifications.create("Đã hủy thay đổi (chưa lưu vào DB)")
                .withType(Notifications.Type.DEFAULT).show();
    }

    /* ===================== Load data ===================== */

    private void reloadTables() {
        allTables = loadTablesFromDB();
        tablesDc.setItems(allTables);
        tablesCb.setItems(allTables);
        tablesCb.clear();

        currentTableFqn = null;
        tableInfo.setText("");
        fieldsDc.setItems(Collections.emptyList());
        colsTwin.clear();
        updateJsonState();
    }

    private List<KeyValueEntity> loadTablesFromDB() {
        List<KeyValueEntity> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String catalog = conn.getCatalog();
            try (ResultSet rs = md.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String schema  = rs.getString("TABLE_SCHEM");
                    String name    = rs.getString("TABLE_NAME");
                    String type    = rs.getString("TABLE_TYPE");
                    String remarks = rs.getString("REMARKS");
                    if (!isSystemSchema(schema)) {
                        KeyValueEntity e = new KeyValueEntity();
                        e.setValue("schema", schema);
                        e.setValue("name", name);
                        e.setValue("type", type);
                        e.setValue("remarks", remarks);
                        list.add(e);
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to load tables from database", ex);
        }
        list.sort(Comparator
                .comparing((KeyValueEntity e) -> Objects.toString(e.getValue("schema"), ""), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> Objects.toString(e.getValue("name"), ""), String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private void loadColumnsForTable(KeyValueEntity table) {
        String schema = Objects.toString(table.getValue("schema"), null);
        String name   = Objects.toString(table.getValue("name"), null);
        fieldsDc.setItems(fetchColumns(schema, name));
    }

    private List<KeyValueEntity> fetchColumns(String schema, String tableName) {
        List<KeyValueEntity> cols = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getColumns(conn.getCatalog(), schema, tableName, "%")) {
                while (rs.next()) {
                    KeyValueEntity c = new KeyValueEntity();
                    c.setValue("name", rs.getString("COLUMN_NAME"));
                    c.setValue("dataType", rs.getString("TYPE_NAME"));
                    c.setValue("size", safeInt(rs, "COLUMN_SIZE"));
                    c.setValue("nullable", isNullable(rs));
                    c.setValue("default", rs.getString("COLUMN_DEF"));
                    c.setValue("remarks", rs.getString("REMARKS"));
                    cols.add(c);
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to load columns for " + schema + "." + tableName, ex);
        }
        return cols;
    }

    /* ===================== JSON state ===================== */

    private void updateJsonState() {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("table", currentTableFqn);

            List<String> selectedColumns = colsTwin.getValue() == null
                    ? Collections.emptyList()
                    : colsTwin.getValue().stream()
                    .map(kv -> Objects.toString(kv.getValue("name"), ""))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            payload.put("columns", selectedColumns);

            String json = objectMapper.writeValueAsString(payload);
            this.currentSelectionJson = json;
            jsonPreview.setText(json); // bị ẩn, chỉ để debug nội bộ
        } catch (Exception ex) {
            this.currentSelectionJson = "{}";
            jsonPreview.setText("{}");
        }
    }

    /* ===================== Template Excel ===================== */

    private void onTemplateUploadSucceeded(String originalName) {
        if (currentTableFqn == null || currentTableFqn.isBlank()) {
            notifications.create("Chưa chọn bảng để gắn template").withType(Notifications.Type.WARNING).show();
            return;
        }
        String fn = Optional.ofNullable(originalName).orElse("").toLowerCase(Locale.ROOT);
        if (!(fn.endsWith(".xlsx") || fn.endsWith(".xlsm"))) {
            notifications.create("Chỉ chấp nhận .xlsx hoặc .xlsm").withType(Notifications.Type.WARNING).show();
            return;
        }
        try (InputStream is = templateBuffer.getInputStream()) {
            // lưu vào FileStorage, path tự sinh, giữ tên file gốc
            FileRef ref = fileStorage.saveStream(originalName, is);

            SheetingConfig cfg = loadOrCreateCfg(currentTableFqn);
            cfg.setTableName(currentTableFqn);
            if (currentSelectionJson == null || currentSelectionJson.isBlank()) {
                updateJsonState();
            }
            cfg.setColumnsJson(currentSelectionJson != null && !currentSelectionJson.isBlank() ? currentSelectionJson : "{}");
            // cần field templateRef trong entity SheetingConfig
            cfg.setTemplateRef(ref);

            dataManager.save(cfg);

            notifications.create("Đã lưu template cho " + currentTableFqn)
                    .withType(Notifications.Type.SUCCESS).show();
            refreshTemplateUiForCurrentTable();
        } catch (Exception ex) {
            notifications.create("Lỗi lưu template: " + ex.getMessage())
                    .withType(Notifications.Type.ERROR).show();
        }
    }

    private void onDownloadTemplateClicked(ClickEvent<Button> event) {
        downloadCurrentTemplate();
    }

    private void downloadCurrentTemplate() {
        if (currentTableFqn == null || currentTableFqn.isBlank()) {
            notifications.create("Chưa chọn bảng").withType(Notifications.Type.WARNING).show();
            return;
        }
        Optional<SheetingConfig> opt = loadCfg(currentTableFqn);
        if (opt.isPresent() && opt.get().getTemplateRef() != null) {
            downloader.download(opt.get().getTemplateRef());
        } else {
            notifications.create("Bảng này chưa có template").withType(Notifications.Type.DEFAULT).show();
        }
    }

    private void refreshTemplateUiForCurrentTable() {
        if (currentTableFqn == null || currentTableFqn.isBlank()) {
            templateInfo.setText("");
            downloadTemplateBtn.setEnabled(false);
            return;
        }
        Optional<SheetingConfig> opt = loadCfg(currentTableFqn);
        if (opt.isPresent() && opt.get().getTemplateRef() != null) {
            FileRef ref = opt.get().getTemplateRef();
            templateInfo.setText("Template: " + ref.getFileName());
            downloadTemplateBtn.setEnabled(true);
        } else {
            templateInfo.setText("Chưa có template");
            downloadTemplateBtn.setEnabled(false);
        }
    }

    private Optional<SheetingConfig> loadCfg(String tableFqn) {
        return dataManager.load(SheetingConfig.class)
                .query("select e from SheetingConfig e where e.tableName = :t")
                .parameter("t", tableFqn)
                .optional();
    }

    private SheetingConfig loadOrCreateCfg(String tableFqn) {
        return loadCfg(tableFqn).orElseGet(() -> {
            SheetingConfig c = dataManager.create(SheetingConfig.class);
            c.setTableName(tableFqn);
            return c;
        });
    }

    /* ===================== Export helpers ===================== */

    private Workbook createWorkbookFromTemplateIfAny() throws Exception {
        // tìm template theo bảng
        Optional<SheetingConfig> opt = dataManager.load(SheetingConfig.class)
                .query("select e from SheetingConfig e where e.tableName = :t")
                .parameter("t", currentTableFqn)
                .optional();

        if (opt.isPresent() && opt.get().getTemplateRef() != null) {
            try (InputStream is = fileStorage.openStream(opt.get().getTemplateRef())) {
                // Ở phiên bản POI hiện tại, create(InputStream) chủ yếu ném IOException/EncryptedDocumentException
                // Bắt Exception cho chắc, nếu lỗi thì fallback về workbook trống
                return WorkbookFactory.create(is);
            } catch (Exception e) {
                return new XSSFWorkbook();
            }
        }
        return new XSSFWorkbook();
    }

    private Sheet resolveTargetSheet(Workbook wb) {
        // ưu tiên sheet tên "Data", không có thì lấy sheet đầu, không có nữa thì tạo mới
        Sheet s = wb.getSheet("Data");
        if (s != null) return s;
        if (wb.getNumberOfSheets() > 0) return wb.getSheetAt(0);
        return wb.createSheet("Data");
    }

    private int[] resolveStartPositionFromNamedRange(Workbook wb, String rangeName) {
        // nếu có Named Range (ví dụ "DATA"), bắt đầu tại topleft của vùng đó
        Name nm = wb.getName(rangeName);
        if (nm != null && nm.getRefersToFormula() != null) {
            try {
                AreaReference ar = new AreaReference(nm.getRefersToFormula(), SpreadsheetVersion.EXCEL2007);
                CellReference tl = ar.getFirstCell();
                return new int[]{tl.getRow(), tl.getCol()};
            } catch (Exception ignore) {
                // rớt về mặc định
            }
        }
        // mặc định: header ở hàng 0, dữ liệu bắt đầu từ hàng 1, cột 0
        return new int[]{0, 0};
    }

    private Row getOrCreateRow(Sheet sheet, int rowIdx) {
        Row r = sheet.getRow(rowIdx);
        return r != null ? r : sheet.createRow(rowIdx);
    }

    private Cell getOrCreateCell(Row row, int colIdx) {
        Cell c = row.getCell(colIdx);
        return c != null ? c : row.createCell(colIdx);
    }

    private void writeCellValue(Cell cell, Object val, Workbook wb, DataFormatter formatter) {
        if (val == null) {
            cell.setBlank();
            return;
        }
        if (val instanceof Number num) {
            cell.setCellValue(num.doubleValue());
            return;
        }
        if (val instanceof Boolean b) {
            cell.setCellValue(b);
            return;
        }
        if (val instanceof java.sql.Date d) {
            cell.setCellValue(new java.util.Date(d.getTime()));
            applyDateStyle(cell, wb, "yyyy-mm-dd");
            return;
        }
        if (val instanceof java.sql.Timestamp ts) {
            cell.setCellValue(new java.util.Date(ts.getTime()));
            applyDateStyle(cell, wb, "yyyy-mm-dd hh:mm:ss");
            return;
        }
        if (val instanceof java.sql.Time t) {
            cell.setCellValue(t.toString()); // giữ nguyên chuỗi HH:mm:ss
            return;
        }
        // chuỗi/khác
        cell.setCellValue(String.valueOf(val));
    }

    private void applyDateStyle(Cell cell, Workbook wb, String fmt) {
        CreationHelper ch = wb.getCreationHelper();
        short df = ch.createDataFormat().getFormat(fmt);
        CellStyle cs = wb.createCellStyle();
        cs.setDataFormat(df);
        cell.setCellStyle(cs);
    }

    private String[] splitSchemaAndTable(String fqn) {
        int p = fqn.indexOf('.');
        if (p > 0) return new String[]{fqn.substring(0, p), fqn.substring(p + 1)};
        return new String[]{null, fqn};
    }

    private String buildSelectSql(DatabaseMetaData md, String schema, String table, List<String> columns) throws SQLException {
        String qi = normalizeQuote(md.getIdentifierQuoteString()); // dấu quote cho identifier
        String tbl = (schema == null || schema.isBlank())
                ? quoteIdent(qi, table)
                : quoteIdent(qi, schema) + "." + quoteIdent(qi, table);

        String cols = columns.stream()
                .map(c -> quoteIdent(qi, c))
                .collect(Collectors.joining(", "));

        // không ORDER BY để tránh ảnh hưởng hiệu năng; cần thì tự thêm sau
        return "SELECT " + cols + " FROM " + tbl;
    }

    private String normalizeQuote(String q) {
        if (q == null) return "";
        q = q.trim();
        // một số JDBC trả về " " khi không có quote
        return q.equals("") || q.equals(" ") ? "" : q;
    }

    private String quoteIdent(String quote, String ident) {
        if (quote.isEmpty()) return ident;
        // có thể escape bên trong nếu cần; đa số DB không cần
        return quote + ident + quote;
    }

    /* ===================== utils ===================== */

    private String fqn(KeyValueEntity e) {
        String schema = Objects.toString(e.getValue("schema"), "");
        String name   = Objects.toString(e.getValue("name"), "");
        return schema == null || schema.isEmpty() ? name : schema + "." + name;
    }

    private String quickInfo(KeyValueEntity e) {
        String type    = Objects.toString(e.getValue("type"), "");
        String remarks = Objects.toString(e.getValue("remarks"), "");
        return (type.isEmpty() ? "" : "[" + type + "] ") + remarks;
    }

    private int safeInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? 0 : v;
    }

    private boolean isNullable(ResultSet rs) throws SQLException {
        String s = rs.getString("IS_NULLABLE");
        if (s != null) return "YES".equalsIgnoreCase(s);
        int flag = rs.getInt("NULLABLE");
        return flag != DatabaseMetaData.columnNoNulls;
    }

    private boolean isSystemSchema(String schema) {
        if (schema == null) return false;
        String s = schema.toLowerCase(Locale.ROOT);
        return s.startsWith("pg_")
                || s.equals("information_schema")
                || s.equals("mysql")
                || s.equals("performance_schema")
                || s.equals("sys")
                || s.equals("system")
                || (s.startsWith("sys") && s.length() > 3);
    }
}
