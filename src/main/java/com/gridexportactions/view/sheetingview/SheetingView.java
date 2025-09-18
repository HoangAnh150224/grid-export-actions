package com.gridexportactions.view.sheetingview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridexportactions.entity.SheetingConfig;
import com.gridexportactions.view.main.MainView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
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
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Route(value = "sheeting-view", layout = MainView.class)
@ViewController("SheetingView")
@ViewDescriptor(value = "sheeting-view.xml", path = "sheeting-view.xml")
public class SheetingView extends StandardView {

    /* ===================== Dependencies & Components ===================== */
    @Autowired @Qualifier("dataSource") private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DataManager dataManager;
    @Autowired private Notifications notifications;
    @Autowired private FileStorage fileStorage;

    // Data containers
    @ViewComponent("tablesDc")   private KeyValueCollectionContainer tablesDc;
    @ViewComponent("fieldsDc")   private KeyValueCollectionContainer fieldsDc;
    @ViewComponent("selectedDc") private KeyValueCollectionContainer selectedDc;

    // UI components
    @ViewComponent private ComboBox<KeyValueEntity> tablesCb;
    @ViewComponent private DataGrid<KeyValueEntity> fieldsGrid;
    @ViewComponent private DataGrid<KeyValueEntity> varsGrid;
    @ViewComponent private Span tableInfo;
    @ViewComponent private Span jsonPreview;
    @ViewComponent private FileStorageUploadField templateUpload;
    @ViewComponent private Checkbox templateHasHeaderCb;
    @ViewComponent private TextField sheetNameField;

    /* ===================== State ===================== */
    private String currentTableFqn;
    private String templateUploadedName;
    private final Map<String, String> nameToVar = new LinkedHashMap<>();

    // NEW: giữ toàn bộ metadata cột gốc của bảng đang chọn
    private List<KeyValueEntity> allFields = Collections.emptyList();

    /* ===================== Lifecycle ===================== */
    @Subscribe
    public void onInit(InitEvent event) {
        jsonPreview.setVisible(false);
        if (templateHasHeaderCb != null) templateHasHeaderCb.setValue(Boolean.TRUE);
        if (sheetNameField != null) sheetNameField.setValue("Export");

        // Upload template -> ./app-templates
        if (templateUpload != null) {
            templateUpload.addFileUploadSucceededListener(e -> {
                FileRef ref = templateUpload.getValue();
                if (ref == null) return;
                if (isBlank(currentTableFqn)) { warn("Chưa chọn bảng"); return; }
                try (InputStream is = fileStorage.openStream(ref)) {
                    Path dir = Paths.get("./app-templates");
                    Files.createDirectories(dir);
                    Files.copy(is, dir.resolve(ref.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    templateUploadedName = ref.getFileName();
                    info("Đã lưu template: " + templateUploadedName);
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

        // Chọn bảng
        tablesCb.setItemLabelGenerator(this::fqn);
        tablesCb.addValueChangeListener(e -> onTableSelected(e.getValue()));

        // Cập nhật JSON + refresh lưới trái khi dữ liệu thay đổi
        selectedDc.addItemPropertyChangeListener(e -> { refreshLeftGrid(); updateJsonState(); });
        selectedDc.addCollectionChangeListener(e -> { refreshLeftGrid(); updateJsonState(); });
        if (sheetNameField != null) sheetNameField.addValueChangeListener(e -> updateJsonState());
        if (templateHasHeaderCb != null) templateHasHeaderCb.addValueChangeListener(e -> updateJsonState());

        // Biến cột tplVar thành ô nhập trực tiếp
        setupTplVarInlineEditor();

        reloadTables();
    }

    private void setupTplVarInlineEditor() {
        DataGrid.Column<KeyValueEntity> tplCol = varsGrid.getColumnByKey("tplVar");
        if (tplCol == null) return;

        tplCol.setRenderer(new ComponentRenderer<>(item -> {
            TextField tf = new TextField();
            tf.setWidthFull();
            tf.setPlaceholder("Nhập tên biến…");
            tf.setValue(Optional.ofNullable((String) item.getValue("tplVar")).orElse(""));
            tf.addValueChangeListener(ev -> {
                String newVal = trimOrNull(ev.getValue());
                item.setValue("tplVar", newVal == null ? "" : newVal);
                String colName = nvl(item.getValue("name"));
                if (newVal == null) nameToVar.remove(colName); else nameToVar.put(colName, newVal);
                selectedDc.replaceItem(item); // refresh container
                updateJsonState();
            });
            return tf;
        }));
    }

    /* ===================== Buttons ===================== */
    @Subscribe("saveBtn")
    public void onSave(ClickEvent<Button> event) {
        if (isBlank(currentTableFqn)) { warn("Chưa chọn bảng"); return; }
        if (selectedDc.getItems() == null || selectedDc.getItems().isEmpty()) { warn("Chưa chọn cột"); return; }
        try {
            String json = objectMapper.writeValueAsString(buildPayload());
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
            jsonPreview.setText(json);
            info("Đã lưu cấu hình cho " + currentTableFqn);
        } catch (Exception ex) {
            error("Lỗi tạo JSON cấu hình: " + ex.getMessage());
        }
    }

    @Subscribe("cancelBtn")
    public void onCancel(ClickEvent<Button> event) {
        resetState();
        info("Đã hủy thay đổi (chưa lưu DB)");
    }

    @Subscribe("addBtn")
    public void onAdd(ClickEvent<Button> e) {
        addByNames(fieldsGrid.getSelectedItems().stream()
                .map(kv -> nvl(kv.getValue("name")))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    @Subscribe("addAllBtn")
    public void onAddAll(ClickEvent<Button> e) {
        addByNames(Optional.ofNullable(fieldsDc.getItems()).orElse(List.of()).stream()
                .map(kv -> nvl(kv.getValue("name")))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    @Subscribe("removeBtn")
    public void onRemove(ClickEvent<Button> e) {
        removeByNames(varsGrid.getSelectedItems().stream()
                .map(kv -> nvl(kv.getValue("name")))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    @Subscribe("removeAllBtn")
    public void onRemoveAll(ClickEvent<Button> e) {
        for (KeyValueEntity kv : Optional.ofNullable(selectedDc.getItems()).orElse(List.of())) {
            String n = nvl(kv.getValue("name"));
            String v = trimOrNull(nvl(kv.getValue("tplVar"), null));
            if (!n.isEmpty() && v != null) nameToVar.put(n, v);
        }
        selectedDc.setItems(Collections.emptyList());
        refreshLeftGrid();
        updateJsonState();
    }

    /* ===================== Grid Events ===================== */
    // Double click bên trái -> Thêm cột
    @Subscribe("fieldsGrid")
    public void onFieldsGridItemDoubleClick(ItemDoubleClickEvent<KeyValueEntity> event) {
        String name = nvl(event.getItem().getValue("name"));
        if (!name.isEmpty()) addByNames(Set.of(name));
    }

    // Double click bên phải -> Gỡ cột
    @Subscribe("varsGrid")
    public void onVarsGridItemDoubleClick(ItemDoubleClickEvent<KeyValueEntity> event) {
        String name = nvl(event.getItem().getValue("name"));
        if (!name.isEmpty()) removeByNames(Set.of(name));
    }

    /* ===================== Data Loading ===================== */
    private void reloadTables() {
        List<KeyValueEntity> all = loadTablesFromDB();
        tablesDc.setItems(all);
        tablesCb.setItems(all);
        tablesCb.clear();
        resetState();
    }

    private void onTableSelected(KeyValueEntity sel) {
        if (sel == null) { resetState(); return; }
        currentTableFqn = fqn(sel);
        tableInfo.setText(quickInfo(sel));
        loadColumnsForTable(sel);
        restoreSavedConfigOrReset();
        updateJsonState();
    }

    private void resetState() {
        currentTableFqn = null;
        tableInfo.setText("");
        fieldsDc.setItems(Collections.emptyList());
        selectedDc.setItems(Collections.emptyList());
        nameToVar.clear();
        templateUploadedName = null;
        allFields = Collections.emptyList();
        if (templateUpload != null) templateUpload.clear();
        refreshLeftGrid(); // safe, sẽ là empty
        updateJsonState();
    }

    private List<KeyValueEntity> loadTablesFromDB() {
        List<KeyValueEntity> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
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
            throw new RuntimeException("Failed to load tables", ex);
        }
        list.sort(Comparator
                .comparing((KeyValueEntity e) -> nvl(e.getValue("schema")), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> nvl(e.getValue("name")), String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private void loadColumnsForTable(KeyValueEntity table) {
        this.allFields = fetchColumns(nvl(table.getValue("schema"), null),
                nvl(table.getValue("name"), null));
        refreshLeftGrid(); // chỉ hiển thị cột chưa được chọn
    }

    private List<KeyValueEntity> fetchColumns(String schema, String table) {
        List<KeyValueEntity> cols = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getColumns(conn.getCatalog(), schema, table, "%")) {
                while (rs.next()) {
                    KeyValueEntity c = new KeyValueEntity();
                    c.setValue("name",     rs.getString("COLUMN_NAME"));
                    c.setValue("dataType", rs.getString("TYPE_NAME"));
                    c.setValue("size",     safeInt(rs, "COLUMN_SIZE"));
                    c.setValue("nullable", isNullable(rs));
                    c.setValue("default",  rs.getString("COLUMN_DEF"));
                    c.setValue("remarks",  rs.getString("REMARKS"));
                    cols.add(c);
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to load columns for " + schema + "." + table, ex);
        }
        return cols;
    }

    /* ===================== Filtering (ẩn cột đã chọn ở lưới trái) ===================== */
    private void refreshLeftGrid() {
        Set<String> selectedNames = currentSelectedNames();
        List<KeyValueEntity> visible = Optional.ofNullable(allFields).orElse(List.of())
                .stream()
                .filter(kv -> !selectedNames.contains(nvl(kv.getValue("name"))))
                .collect(Collectors.toList());
        fieldsDc.setItems(visible);
    }

    /* ===================== Restore & In-Memory State ===================== */
    private void restoreSavedConfigOrReset() {
        if (isBlank(currentTableFqn)) {
            selectedDc.setItems(Collections.emptyList());
            nameToVar.clear();
            refreshLeftGrid();
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

                        LinkedHashSet<String> names = new LinkedHashSet<>();
                        nameToVar.clear();

                        Object cols = json.get("columns");
                        if (cols instanceof Collection<?> list) {
                            for (Object it : list) {
                                if (it instanceof Map<?, ?> m) {
                                    String name = nvl(m.get("name"));
                                    if (!name.isBlank()) {
                                        names.add(name);
                                        String var = trimOrNull(nvl(m.get("tplVar"), null));
                                        if (var != null) nameToVar.put(name, var);
                                    }
                                } else if (it != null) {
                                    names.add(it.toString());
                                }
                            }
                        }
                        // legacy templateVars
                        Object legacyVars = json.get("templateVars");
                        if (legacyVars instanceof Map<?, ?> m) {
                            m.forEach((k, v) -> nameToVar.put(String.valueOf(k), String.valueOf(v)));
                        }

                        selectedDc.setItems(buildRowsFromNames(names));

                        if (sheetNameField != null)
                            sheetNameField.setValue(Objects.toString(json.getOrDefault("sheetName", "Export"), "Export"));
                        if (templateHasHeaderCb != null && json.containsKey("templateHasHeader"))
                            templateHasHeaderCb.setValue(Boolean.parseBoolean(nvl(json.get("templateHasHeader"), "true")));
                        templateUploadedName = trimOrNull(objToStr(json.get("templateUploaded")));
                        jsonPreview.setText(cfg.getColumnsJson());
                    } catch (Exception ignore) {
                        selectedDc.setItems(Collections.emptyList());
                        nameToVar.clear();
                        templateUploadedName = null;
                        if (templateUpload != null) templateUpload.clear();
                    }
                    refreshLeftGrid();
                }, () -> {
                    selectedDc.setItems(Collections.emptyList());
                    nameToVar.clear();
                    templateUploadedName = null;
                    if (templateUpload != null) templateUpload.clear();
                    refreshLeftGrid();
                });
    }

    /** Thêm cột vào selectedDc, tránh trùng, copy metadata từ allFields và giữ tplVar. */
    private void addByNames(Collection<String> names) {
        if (names == null || names.isEmpty()) return;
        Set<String> already = currentSelectedNames();

        Map<String, KeyValueEntity> metaByName = new HashMap<>();
        if (allFields != null) {
            for (KeyValueEntity kv : allFields) {
                metaByName.put(nvl(kv.getValue("name")), kv);
            }
        }

        List<KeyValueEntity> rows = new ArrayList<>(Optional.ofNullable(selectedDc.getItems()).orElse(List.of()));
        for (String raw : names) {
            String n = nvl(raw);
            if (n.isEmpty() || already.contains(n)) continue;
            KeyValueEntity src = metaByName.get(n);
            if (src == null) continue;

            KeyValueEntity row = new KeyValueEntity();
            row.setValue("name", n);
            row.setValue("dataType", src.getValue("dataType"));
            row.setValue("size", src.getValue("size"));
            row.setValue("nullable", src.getValue("nullable"));
            row.setValue("default", src.getValue("default"));
            row.setValue("remarks", src.getValue("remarks"));
            row.setValue("tplVar", nameToVar.getOrDefault(n, ""));
            rows.add(row);
            already.add(n);
        }
        selectedDc.setItems(rows);
        refreshLeftGrid();
        updateJsonState();
    }

    /** Gỡ cột khỏi selectedDc nhưng vẫn nhớ tplVar đã gõ. */
    private void removeByNames(Collection<String> names) {
        if (names == null || names.isEmpty()) return;
        Set<String> toRemove = names.stream().filter(s -> !isBlank(s)).collect(Collectors.toSet());
        List<KeyValueEntity> rows = new ArrayList<>();
        for (KeyValueEntity kv : Optional.ofNullable(selectedDc.getItems()).orElse(List.of())) {
            String n = nvl(kv.getValue("name"));
            if (!toRemove.contains(n)) {
                rows.add(kv);
            } else {
                String var = trimOrNull(nvl(kv.getValue("tplVar"), null));
                if (var != null) nameToVar.put(n, var);
            }
        }
        selectedDc.setItems(rows);
        refreshLeftGrid();
        updateJsonState();
    }

    private Set<String> currentSelectedNames() {
        return Optional.ofNullable(selectedDc.getItems()).orElse(List.of()).stream()
                .map(kv -> nvl(kv.getValue("name")))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<KeyValueEntity> buildRowsFromNames(Collection<String> names) {
        Map<String, KeyValueEntity> metaByName = new HashMap<>();
        if (allFields != null) {
            for (KeyValueEntity kv : allFields) {
                metaByName.put(nvl(kv.getValue("name")), kv);
            }
        }

        List<KeyValueEntity> rows = new ArrayList<>();
        for (String raw : names) {
            String n = nvl(raw);
            if (n.isEmpty()) continue;
            KeyValueEntity src = metaByName.get(n);
            if (src == null) continue;

            KeyValueEntity row = new KeyValueEntity();
            row.setValue("name", n);
            row.setValue("dataType", src.getValue("dataType"));
            row.setValue("size", src.getValue("size"));
            row.setValue("nullable", src.getValue("nullable"));
            row.setValue("default", src.getValue("default"));
            row.setValue("remarks", src.getValue("remarks"));
            row.setValue("tplVar", nameToVar.getOrDefault(n, ""));
            rows.add(row);
        }
        return rows;
    }

    /* ===================== JSON payload ===================== */
    private void updateJsonState() {
        try {
            jsonPreview.setText(objectMapper.writeValueAsString(buildPayload()));
        } catch (Exception ex) {
            jsonPreview.setText("{}");
        }
    }

    private Map<String, Object> buildPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("table", currentTableFqn);
        payload.put("columns",       selectedColumnsStructured());
        payload.put("columnsFlat",   selectedColumnNames()); // compat cũ
        payload.put("templateVars",  selectedVarMap());      // compat cũ
        payload.put("sheetName",     sheetNameField != null ? nvl(sheetNameField.getValue(), "Export") : "Export");
        payload.put("templateHasHeader", templateHasHeaderCb != null && Boolean.TRUE.equals(templateHasHeaderCb.getValue()));
        payload.put("templateUploaded",  templateUploadedName);
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
        return Optional.ofNullable(selectedDc.getItems()).orElse(List.of()).stream()
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

    private static int safeInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? 0 : v;
    }

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

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

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
