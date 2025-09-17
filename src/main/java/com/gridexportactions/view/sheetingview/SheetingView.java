package com.gridexportactions.view.sheetingview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridexportactions.entity.SheetingConfig;
import com.gridexportactions.view.main.MainView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.twincolumn.TwinColumn;
import io.jmix.flowui.component.upload.FileStorageUploadField;
import io.jmix.flowui.model.KeyValueCollectionContainer;
import io.jmix.flowui.view.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
    @ViewComponent("selectedDc") private KeyValueCollectionContainer selectedDc;

    @ViewComponent private ComboBox<KeyValueEntity> tablesCb;
    @ViewComponent private TwinColumn<KeyValueEntity> colsTwin;
    @ViewComponent private DataGrid<KeyValueEntity> varsGrid;

    @ViewComponent private Span tableInfo;
    @ViewComponent private Span jsonPreview;

    // Template + Name Manager
    @ViewComponent private FileStorageUploadField templateUpload;
    @ViewComponent private TextField headerAnchorField;
    @ViewComponent private TextField dataAnchorField;
    @ViewComponent private Checkbox templateHasHeaderCb;
    @ViewComponent private TextField sheetNameField;

    private String currentTableFqn;
    private String templateUploadedName;  // ./app-templates/<name>
    private String currentSelectionJson = "";

    // nhớ mapping name -> tplVar (để preserve khi twin thay đổi)
    private final Map<String, String> nameToVar = new LinkedHashMap<>();

    @Subscribe
    public void onInit(InitEvent event) {
        jsonPreview.setVisible(false);

        if (headerAnchorField != null) headerAnchorField.setPlaceholder("HEADER_START");
        if (dataAnchorField != null)   dataAnchorField.setPlaceholder("DATA_START");
        if (templateHasHeaderCb != null) templateHasHeaderCb.setValue(Boolean.TRUE);
        if (sheetNameField != null) sheetNameField.setValue("Export");

        // Upload template
        if (templateUpload != null) {
            templateUpload.addFileUploadSucceededListener(e -> {
                FileRef ref = templateUpload.getValue();
                if (ref == null) return;
                if (isBlank(currentTableFqn)) { warn("Chưa chọn bảng"); return; }
                try (InputStream is = fileStorage.openStream(ref)) {
                    String fileName = ref.getFileName();
                    Path dir = Paths.get("./app-templates");
                    Files.createDirectories(dir);
                    Files.copy(is, dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                    templateUploadedName = fileName;
                    info("Đã lưu template vào app-templates: " + fileName);
                    updateJsonState();
                } catch (Exception ex) {
                    error("Lưu template thất bại: " + ex.getMessage());
                }
            });
            templateUpload.addValueChangeListener(e -> {
                if (e.getValue() == null) templateUploadedName = null;
                updateJsonState();
            });
        }

        tablesCb.setItemLabelGenerator(this::fqn);
        colsTwin.setItemLabelGenerator(kv -> Objects.toString(kv.getValue("name"), ""));
        tablesCb.addValueChangeListener(e -> onTableSelected(e.getValue()));

        // Chỉ nghe valueChange chuẩn của TwinColumn để đồng bộ panel phải
        colsTwin.addValueChangeListener(e -> {
            syncVarsFromTwin();
            selectFirstRowIfAny();
            updateJsonState();
        });

        // Khi sửa trong grid/form
        selectedDc.addItemPropertyChangeListener(e -> updateJsonState());
        selectedDc.addCollectionChangeListener(e -> updateJsonState());

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
        if (selectedDc.getItems() == null || selectedDc.getItems().isEmpty()) {
            warn("Chưa chọn cột để lưu cấu hình"); return;
        }

        Map<String, Object> payload = buildPayload();
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
        selectedDc.setItems(Collections.emptyList());
        nameToVar.clear();

        if (templateUpload != null) templateUpload.clear();
        templateUploadedName = null;
        if (headerAnchorField != null) headerAnchorField.clear();
        if (dataAnchorField != null) dataAnchorField.clear();
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
        selectedDc.setItems(Collections.emptyList());
        nameToVar.clear();
        templateUploadedName = null;
        updateJsonState();
    }

    private void onTableSelected(KeyValueEntity sel) {
        if (sel == null) {
            currentTableFqn = null;
            tableInfo.setText("");
            fieldsDc.setItems(Collections.emptyList());
            colsTwin.clear();
            selectedDc.setItems(Collections.emptyList());
            nameToVar.clear();
            templateUploadedName = null;
            updateJsonState();
            return;
        }
        currentTableFqn = fqn(sel);
        tableInfo.setText(quickInfo(sel));
        loadColumnsForTable(sel);
        restoreSavedConfigOrReset();
        syncVarsFromTwin();
        selectFirstRowIfAny();
        updateJsonState();
    }

    private void selectFirstRowIfAny() {
        Collection<KeyValueEntity> items = selectedDc.getItems();
        if (items != null && !items.isEmpty()) {
            varsGrid.select(items.iterator().next());
        } else {
            varsGrid.deselectAll();
        }
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
        if (isBlank(currentTableFqn)) {
            colsTwin.clear();
            selectedDc.setItems(Collections.emptyList());
            nameToVar.clear();
            return;
        }

        dataManager.load(SheetingConfig.class)
                .query("select e from SheetingConfig e where e.tableName = :t")
                .parameter("t", currentTableFqn)
                .optional()
                .ifPresentOrElse(cfg -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> json = objectMapper.readValue(cfg.getColumnsJson(), Map.class);

                        Set<String> names = new LinkedHashSet<>();
                        nameToVar.clear();

                        Object cols = json.get("columns");
                        Object legacyVars = json.get("templateVars");
                        Map<String, String> legacyVarMap = null;
                        if (legacyVars instanceof Map<?, ?> m) {
                            legacyVarMap = new HashMap<>();
                            for (Map.Entry<?, ?> en : m.entrySet()) {
                                legacyVarMap.put(String.valueOf(en.getKey()), String.valueOf(en.getValue()));
                            }
                        }

                        if (cols instanceof Collection<?> list) {
                            for (Object it : list) {
                                if (it == null) continue;
                                if (it instanceof Map<?, ?> m) {
                                    String name = nvl(m.get("name"));
                                    if (!name.isBlank()) {
                                        names.add(name);
                                        String var = trimOrNull(nvl(m.get("tplVar"), null));
                                        if (var != null) nameToVar.put(name, var);
                                    }
                                } else {
                                    String name = it.toString();
                                    if (!name.isBlank()) {
                                        names.add(name);
                                        if (legacyVarMap != null && legacyVarMap.containsKey(name)) {
                                            nameToVar.put(name, legacyVarMap.get(name));
                                        }
                                    }
                                }
                            }
                        }

                        Collection<KeyValueEntity> all = fieldsDc.getItems();
                        if (all != null && !all.isEmpty()) {
                            LinkedHashSet<KeyValueEntity> selected = all.stream()
                                    .filter(kv -> names.contains(nvl(kv.getValue("name"))))
                                    .collect(Collectors.toCollection(LinkedHashSet::new));
                            colsTwin.setValue(selected);
                        }

                        if (sheetNameField != null) {
                            String sheet = Objects.toString(json.getOrDefault("sheetName", "Export"), "Export");
                            sheetNameField.setValue(sheet);
                        }
                        if (headerAnchorField != null) headerAnchorField.setValue(nvl(json.get("headerAnchor")));
                        if (dataAnchorField != null)   dataAnchorField.setValue(nvl(json.get("dataAnchor")));
                        if (templateHasHeaderCb != null && json.containsKey("templateHasHeader")) {
                            templateHasHeaderCb.setValue(Boolean.parseBoolean(nvl(json.get("templateHasHeader"), "true")));
                        }
                        templateUploadedName = trimOrNull(objToStr(json.get("templateUploaded")));

                        currentSelectionJson = cfg.getColumnsJson();
                        jsonPreview.setText(currentSelectionJson);
                    } catch (Exception ignore) {
                        colsTwin.clear();
                        selectedDc.setItems(Collections.emptyList());
                        nameToVar.clear();
                        templateUploadedName = null;
                        if (templateUpload != null) templateUpload.clear();
                    }
                }, () -> {
                    colsTwin.clear();
                    selectedDc.setItems(Collections.emptyList());
                    nameToVar.clear();
                    templateUploadedName = null;
                    if (templateUpload != null) templateUpload.clear();
                });
    }

    /** TwinColumn -> selectedDc, copy metadata và giữ tplVar đã nhập. */
    private void syncVarsFromTwin() {
        // preserve tplVar hiện có trước khi rebuild
        Map<String, String> existingTplVars = new HashMap<>();
        Collection<KeyValueEntity> oldItems = selectedDc.getItems();
        if (oldItems != null) {
            for (KeyValueEntity kv : oldItems) {
                String name = nvl(kv.getValue("name"));
                String var  = trimOrNull(nvl(kv.getValue("tplVar"), null));
                if (!name.isEmpty() && var != null) existingTplVars.put(name, var);
            }
        }

        Collection<KeyValueEntity> sel = colsTwin.getValue();
        Map<String, KeyValueEntity> metaByName = new HashMap<>();
        if (fieldsDc.getItems() != null) {
            for (KeyValueEntity kv : fieldsDc.getItems()) {
                metaByName.put(nvl(kv.getValue("name")), kv);
            }
        }
        List<KeyValueEntity> rows = new ArrayList<>();
        if (sel != null) {
            for (KeyValueEntity kv : sel) {
                String name = nvl(kv.getValue("name"));
                if (name.isBlank()) continue;
                KeyValueEntity src = metaByName.get(name);
                KeyValueEntity row = new KeyValueEntity();
                row.setValue("name", name);
                row.setValue("dataType", src != null ? src.getValue("dataType") : null);
                row.setValue("size", src != null ? src.getValue("size") : null);
                row.setValue("nullable", src != null ? src.getValue("nullable") : null);
                row.setValue("default", src != null ? src.getValue("default") : null);
                row.setValue("remarks", src != null ? src.getValue("remarks") : null);
                row.setValue("tplVar", existingTplVars.getOrDefault(name, ""));
                rows.add(row);
            }
        }
        selectedDc.setItems(rows);
    }

    private void addToSelectedByName(String name) {
        if (fieldsDc.getItems() == null) return;
        KeyValueEntity found = fieldsDc.getItems().stream()
                .filter(kv -> name.equals(Objects.toString(kv.getValue("name"), "")))
                .findFirst().orElse(null);
        if (found == null) return;
        LinkedHashSet<KeyValueEntity> cur = new LinkedHashSet<>();
        if (colsTwin.getValue() != null) cur.addAll(colsTwin.getValue());
        if (!cur.contains(found)) cur.add(found);
        colsTwin.setValue(cur);
    }

    private void selectInRightPaneByName(String name) {
        Collection<KeyValueEntity> items = selectedDc.getItems();
        if (items == null) return;
        for (KeyValueEntity row : items) {
            if (name.equals(Objects.toString(row.getValue("name"), ""))) {
                varsGrid.select(row);
                return;
            }
        }
    }

    private void updateJsonState() {
        try {
            Map<String, Object> payload = buildPayload();
            String json = objectMapper.writeValueAsString(payload);
            this.currentSelectionJson = json;
            jsonPreview.setText(json);
        } catch (Exception ex) {
            this.currentSelectionJson = "{}";
            jsonPreview.setText("{}");
        }
    }

    private Map<String, Object> buildPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("table", currentTableFqn);
        payload.put("columns", selectedColumnsStructured());
        payload.put("columnsFlat", selectedColumnNames()); // compat cũ
        payload.put("templateVars", selectedVarMap());     // compat cũ
        payload.put("sheetName",  sheetNameField != null ? nvl(sheetNameField.getValue(), "Export") : "Export");
        payload.put("headerAnchor", headerAnchorField != null ? trimOrNull(headerAnchorField.getValue()) : null);
        payload.put("dataAnchor",   dataAnchorField != null ? trimOrNull(dataAnchorField.getValue())   : null);
        payload.put("templateHasHeader", templateHasHeaderCb != null && Boolean.TRUE.equals(templateHasHeaderCb.getValue()));
        payload.put("templateUploaded", templateUploadedName);
        return payload;
    }

    private List<Map<String, Object>> selectedColumnsStructured() {
        List<Map<String, Object>> out = new ArrayList<>();
        Collection<KeyValueEntity> items = selectedDc.getItems();
        if (items != null) {
            for (KeyValueEntity kv : items) {
                String name = nvl(kv.getValue("name"));
                String var  = trimOrNull(nvl(kv.getValue("tplVar"), null));
                if (!name.isEmpty()) {
                    if (var == null) nameToVar.remove(name); else nameToVar.put(name, var);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    if (var != null) m.put("tplVar", var);
                    out.add(m);
                }
            }
        }
        return out;
    }

    private List<String> selectedColumnNames() {
        return colsTwin.getValue() == null ? Collections.emptyList()
                : colsTwin.getValue().stream()
                .map(kv -> nvl(kv.getValue("name")))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private Map<String, String> selectedVarMap() {
        Map<String, String> map = new LinkedHashMap<>();
        Collection<KeyValueEntity> items = selectedDc.getItems();
        if (items != null) {
            for (KeyValueEntity kv : items) {
                String name = nvl(kv.getValue("name"));
                String var  = trimOrNull(nvl(kv.getValue("tplVar"), null));
                if (!name.isEmpty() && var != null) map.put(name, var);
            }
        }
        return map;
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
    private static String nvl(Object o) { return o == null ? "" : o.toString(); }
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
