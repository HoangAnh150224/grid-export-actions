package com.gridexportactions.view.sheetingview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridexportactions.entity.SheetingConfig;
import com.gridexportactions.view.main.MainView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Label;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.twincolumn.TwinColumn;
import io.jmix.flowui.component.upload.FileStorageUploadField;
import io.jmix.flowui.model.KeyValueCollectionContainer;
import io.jmix.flowui.view.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Route(value = "sheeting-view", layout = MainView.class)
@ViewController("SheetingView")
@ViewDescriptor(value = "sheeting-view.xml", path = "sheeting-view.xml")
public class SheetingView extends StandardView {

    @Autowired @Qualifier("dataSource") private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DataManager dataManager;
    @Autowired private Notifications notifications;
    @Autowired private FileStorage fileStorage;

    @ViewComponent("tablesDc") private KeyValueCollectionContainer tablesDc;
    @ViewComponent("fieldsDc") private KeyValueCollectionContainer fieldsDc;

    @ViewComponent private ComboBox<KeyValueEntity> tablesCb;
    @ViewComponent private TwinColumn<KeyValueEntity> colsTwin;
    @ViewComponent private Label tableInfo;
    @ViewComponent private Label jsonPreview;

    // Template + Name Manager
    @ViewComponent private FileStorageUploadField templateUpload;
    @ViewComponent private TextField headerAnchorField;
    @ViewComponent private TextField dataAnchorField;
    @ViewComponent private Checkbox templateHasHeaderCb;
    @ViewComponent private TextField sheetNameField;

    private String currentTableFqn;
    private FileRef templateFileRef; // file user vừa upload hoặc file đã lưu trước đó
    private String currentSelectionJson = "";

    @Subscribe
    public void onInit(InitEvent event) {
        jsonPreview.setVisible(false);

        // placeholders & defaults
        if (headerAnchorField != null) headerAnchorField.setPlaceholder("HEADER_START");
        if (dataAnchorField != null)   dataAnchorField.setPlaceholder("DATA_START");
        if (templateHasHeaderCb != null) templateHasHeaderCb.setValue(Boolean.TRUE);
        if (sheetNameField != null) sheetNameField.setValue("Export");

        // lắng nghe upload
        if (templateUpload != null) {
            templateUpload.addFileUploadSucceededListener(e -> {
                templateFileRef = templateUpload.getValue();
                updateJsonState();
            });
            templateUpload.addValueChangeListener(e -> {
                if (e.getValue() == null) templateFileRef = null;
                updateJsonState();
            });
        }

        tablesCb.setItemLabelGenerator(this::fqn);
        colsTwin.setItemLabelGenerator(kv -> Objects.toString(kv.getValue("name"), ""));
        tablesCb.addValueChangeListener(e -> onTableSelected(e.getValue()));

        colsTwin.addValueChangeListener(e -> updateJsonState());
        if (headerAnchorField != null) headerAnchorField.addValueChangeListener(e -> updateJsonState());
        if (dataAnchorField != null)   dataAnchorField.addValueChangeListener(e -> updateJsonState());
        if (templateHasHeaderCb != null) templateHasHeaderCb.addValueChangeListener(e -> updateJsonState());
        if (sheetNameField != null) sheetNameField.addValueChangeListener(e -> updateJsonState());

        reloadTables();
    }

    /* ===================== Actions ===================== */

    @Subscribe("saveBtn")
    public void onSaveBtnClick(ClickEvent<Button> event) {
        if (isBlank(currentTableFqn)) { warn("Chưa chọn bảng"); return; }
        if (colsTwin.getValue() == null || colsTwin.getValue().isEmpty()) {
            warn("Chưa chọn cột để lưu cấu hình"); return;
        }
        // Build JSON payload đúng schema mà SheetingConfigService.parseSpec dùng
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("table", currentTableFqn);
        payload.put("columns", selectedColumns());
        payload.put("sheetName",  sheetNameField != null ? nvl(sheetNameField.getValue(), "Export") : "Export");
        payload.put("headerAnchor", trimOrNull(headerAnchorField != null ? headerAnchorField.getValue() : null));
        payload.put("dataAnchor",   trimOrNull(dataAnchorField   != null ? dataAnchorField.getValue()   : null));
        payload.put("templateHasHeader", templateHasHeaderCb != null && Boolean.TRUE.equals(templateHasHeaderCb.getValue()));

        if (templateFileRef != null) {
            payload.put("templateStorage", templateFileRef.getStorageName());
            payload.put("templateFileId",  templateFileRef.getPath());      // path ~ id
            payload.put("templateFileName", templateFileRef.getFileName());
        } else {
            payload.put("templateStorage", null);
            payload.put("templateFileId",  null);
            payload.put("templateFileName", null);
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            error("Lỗi tạo JSON cấu hình: " + ex.getMessage());
            return;
        }

        SheetingConfig cfg = dataManager.load(SheetingConfig.class)
                .query("select e from SheetingConfig e where e.tableName = :t")
                .parameter("t", currentTableFqn)
                .optional()
                .orElseGet(() -> {
                    SheetingConfig n = dataManager.create(SheetingConfig.class);
                    n.setTableName(currentTableFqn);
                    return n;
                });

        cfg.setColumnsJson(json);
        dataManager.save(cfg);
        info("Đã lưu cấu hình cho " + currentTableFqn);
        currentSelectionJson = json;
        jsonPreview.setText(json);
    }

    @Subscribe("cancelBtn")
    public void onCancelBtnClick(ClickEvent<Button> event) {
        colsTwin.clear();
        if (templateUpload != null) {
            templateUpload.clear();
            templateFileRef = null;
        }
        headerAnchorField.clear();
        dataAnchorField.clear();
        if (templateHasHeaderCb != null) templateHasHeaderCb.setValue(Boolean.TRUE);
        if (sheetNameField != null) sheetNameField.setValue("Export");
        updateJsonState();
        notifications.create("Đã hủy thay đổi (chưa lưu vào DB)")
                .withType(Notifications.Type.DEFAULT).show();
    }

    /* ===================== Loaders ===================== */

    private void reloadTables() {
        List<KeyValueEntity> allTables = loadTablesFromDB();
        tablesDc.setItems(allTables);
        tablesCb.setItems(allTables);
        tablesCb.clear();

        currentTableFqn = null;
        tableInfo.setText("");
        fieldsDc.setItems(Collections.emptyList());
        colsTwin.clear();
        templateFileRef = null;
        updateJsonState();
    }

    private void onTableSelected(KeyValueEntity sel) {
        if (sel == null) {
            currentTableFqn = null;
            tableInfo.setText("");
            fieldsDc.setItems(Collections.emptyList());
            colsTwin.clear();
            templateFileRef = null;
            updateJsonState();
            return;
        }
        currentTableFqn = fqn(sel);
        tableInfo.setText(quickInfo(sel));
        loadColumnsForTable(sel);
        restoreSavedConfigOrReset();
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
                .comparing((KeyValueEntity e) -> nvl(e.getValue("schema")), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> nvl(e.getValue("name")), String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private void loadColumnsForTable(KeyValueEntity table) {
        String schema = nvl(table.getValue("schema"), null);
        String name   = nvl(table.getValue("name"), null);
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

    /* ===================== Restore & State ===================== */

    private void restoreSavedConfigOrReset() {
        if (isBlank(currentTableFqn)) { colsTwin.clear(); return; }

        dataManager.load(SheetingConfig.class)
                .query("select e from SheetingConfig e where e.tableName = :t")
                .parameter("t", currentTableFqn)
                .optional()
                .ifPresentOrElse(cfg -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> json = objectMapper.readValue(cfg.getColumnsJson(), Map.class);

                        // columns
                        Object cols = json.get("columns");
                        if (cols instanceof Collection<?> colList) {
                            Set<String> names = colList.stream().filter(Objects::nonNull)
                                    .map(Object::toString).filter(s -> !s.isBlank())
                                    .collect(Collectors.toCollection(LinkedHashSet::new));
                            Collection<KeyValueEntity> all = fieldsDc.getItems();
                            if (all != null && !all.isEmpty()) {
                                LinkedHashSet<KeyValueEntity> selected = all.stream()
                                        .filter(kv -> names.contains(nvl(kv.getValue("name"))))
                                        .collect(Collectors.toCollection(LinkedHashSet::new));
                                colsTwin.setValue(selected);
                            }
                        }

                        // sheet name
                        if (sheetNameField != null) {
                            String sheet = Objects.toString(json.getOrDefault("sheetName", "Export"), "Export");
                            sheetNameField.setValue(sheet);
                        }

                        // anchors
                        if (headerAnchorField != null) headerAnchorField.setValue(nvl(json.get("headerAnchor")));
                        if (dataAnchorField != null)   dataAnchorField.setValue(nvl(json.get("dataAnchor")));
                        if (templateHasHeaderCb != null && json.containsKey("templateHasHeader")) {
                            templateHasHeaderCb.setValue(Boolean.parseBoolean(nvl(json.get("templateHasHeader"), "true")));
                        }

                        // template (FileRef)
                        String storage = trimOrNull(objToStr(json.get("templateStorage")));
                        String path    = trimOrNull(objToStr(json.get("templateFileId")));   // path ~ id
                        String name    = trimOrNull(objToStr(json.get("templateFileName")));
                        if (storage != null && path != null && name != null) {
                            templateFileRef = new FileRef(storage, path, name);
                            // Hiển thị lại tên file (UploadField hỗ trợ setValue(FileRef))
                            if (templateUpload != null) templateUpload.setValue(templateFileRef);
                            // Validate stream khả dụng (optional)
                            try (InputStream is = fileStorage.openStream(templateFileRef)) {
                                // no-op
                            } catch (Exception ex) {
                                // nếu file không còn trong storage -> clear
                                templateFileRef = null;
                                if (templateUpload != null) templateUpload.clear();
                            }
                        } else {
                            templateFileRef = null;
                            if (templateUpload != null) templateUpload.clear();
                        }

                        currentSelectionJson = cfg.getColumnsJson();
                        jsonPreview.setText(currentSelectionJson);
                    } catch (Exception ignore) {
                        colsTwin.clear();
                        templateFileRef = null;
                        if (templateUpload != null) templateUpload.clear();
                    }
                }, () -> {
                    colsTwin.clear();
                    templateFileRef = null;
                    if (templateUpload != null) templateUpload.clear();
                });
    }

    private void updateJsonState() {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("table", currentTableFqn);
            payload.put("columns", selectedColumns());
            payload.put("sheetName",  sheetNameField != null ? nvl(sheetNameField.getValue(), "Export") : "Export");
            payload.put("headerAnchor", headerAnchorField != null ? trimOrNull(headerAnchorField.getValue()) : null);
            payload.put("dataAnchor",   dataAnchorField != null ? trimOrNull(dataAnchorField.getValue())   : null);
            payload.put("templateHasHeader", templateHasHeaderCb != null && Boolean.TRUE.equals(templateHasHeaderCb.getValue()));
            if (templateFileRef != null) {
                payload.put("templateStorage", templateFileRef.getStorageName());
                payload.put("templateFileId",  templateFileRef.getPath());
                payload.put("templateFileName", templateFileRef.getFileName());
            } else {
                payload.put("templateStorage", null);
                payload.put("templateFileId",  null);
                payload.put("templateFileName", null);
            }

            String json = objectMapper.writeValueAsString(payload);
            this.currentSelectionJson = json;
            jsonPreview.setText(json);
        } catch (Exception ex) {
            this.currentSelectionJson = "{}";
            jsonPreview.setText("{}");
        }
    }

    private List<String> selectedColumns() {
        return colsTwin.getValue() == null ? Collections.emptyList()
                : colsTwin.getValue().stream()
                .map(kv -> nvl(kv.getValue("name")))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /* ===================== Utils ===================== */

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static int safeInt(ResultSet rs, String col) throws SQLException { int v = rs.getInt(col); return rs.wasNull() ? 0 : v; }
    private static boolean isNullable(ResultSet rs) throws SQLException {
        String s = rs.getString("IS_NULLABLE");
        if (s != null) return "YES".equalsIgnoreCase(s);
        int flag = rs.getInt("NULLABLE");
        return flag != DatabaseMetaData.columnNoNulls;
    }
    private static boolean isSystemSchema(String schema) {
        if (schema == null) return false;
        String s = schema.toLowerCase(Locale.ROOT);
        return s.startsWith("pg_") || s.equals("information_schema") || s.equals("mysql")
                || s.equals("performance_schema") || s.equals("sys") || s.equals("system")
                || (s.startsWith("sys") && s.length() > 3);
    }
    private static String[] splitSchemaAndTable(String fqn) {
        int p = fqn.indexOf('.');
        return p > 0 ? new String[]{fqn.substring(0, p), fqn.substring(p + 1)} : new String[]{null, fqn};
    }
    private static String nvl(Object o) { return nvl(o, ""); }
    private static String nvl(Object o, String def) { return o == null ? def : o.toString(); }
    private static String objToStr(Object o) { return o == null ? null : o.toString(); }
    private static String trimOrNull(String s) { if (s == null) return null; String t = s.trim(); return t.isEmpty() ? null : t; }

    private void info(String msg) { notifications.create(msg).withType(Notifications.Type.SUCCESS).show(); }
    private void warn(String msg) { notifications.create(msg).withType(Notifications.Type.WARNING).show(); }
    private void error(String msg) { notifications.create(msg).withType(Notifications.Type.ERROR).show(); }

    private String fqn(KeyValueEntity e) {
        String schema = nvl(e.getValue("schema"), "");
        String name   = nvl(e.getValue("name"), "");
        return schema.isEmpty() ? name : schema + "." + name;
    }
    private String quickInfo(KeyValueEntity e) {
        String type = nvl(e.getValue("type"), "");
        String remarks = nvl(e.getValue("remarks"), "");
        return (type.isEmpty() ? "" : "[" + type + "] ") + remarks;
    }
}
