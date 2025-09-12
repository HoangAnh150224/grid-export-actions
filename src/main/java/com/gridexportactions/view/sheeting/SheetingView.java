package com.gridexportactions.view.sheeting;

import com.gridexportactions.entity.SheetingConfig;
import com.gridexportactions.view.main.MainView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.twincolumn.TwinColumn;
import io.jmix.flowui.model.KeyValueCollectionContainer;
import io.jmix.flowui.view.*;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
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

    @ViewComponent("tablesDc") private KeyValueCollectionContainer tablesDc;
    @ViewComponent("fieldsDc") private KeyValueCollectionContainer fieldsDc;

    @ViewComponent private ComboBox<KeyValueEntity> tablesCb;
    @ViewComponent private TwinColumn<KeyValueEntity> colsTwin;
    @ViewComponent private com.vaadin.flow.component.html.Label tableInfo;
    @ViewComponent private com.vaadin.flow.component.html.Label jsonPreview;

    private List<KeyValueEntity> allTables = new ArrayList<>();
    private String currentTableFqn;
    private String currentSelectionJson = "";

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
            } else {
                currentTableFqn = fqn(sel);
                tableInfo.setText(quickInfo(sel));
                loadColumnsForTable(sel);
                boolean applied = applySavedSelection(); // <-- cố gắng nạp lại
                if (!applied) {
                    colsTwin.clear(); // không có cấu hình thì reset rỗng
                }
                updateJsonState();
            }
        });

        colsTwin.addValueChangeListener(e -> updateJsonState());

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

    /* ===================== NÚT LƯU / HỦY ===================== */

    @Subscribe("saveBtn")
    public void onSaveBtnClick(ClickEvent<Button> event) {
        if (currentTableFqn == null || currentTableFqn.isBlank()) {
            notifications.create("Chưa chọn bảng").withType(Notifications.Type.WARNING).show();
            return;
        }
        if (currentSelectionJson == null || currentSelectionJson.isBlank()) {
            updateJsonState(); // phòng hờ
        }

        // Tìm theo tableName, có thì update, không thì tạo mới
        Optional<SheetingConfig> opt = dataManager.load(SheetingConfig.class)
                .query("select e from SheetingConfig e where e.tableName = :t")
                .parameter("t", currentTableFqn)
                .optional();


        SheetingConfig cfg = opt.orElseGet(SheetingConfig::new);
        cfg.setTableName(currentTableFqn);
        cfg.setColumnsJson(currentSelectionJson);

        dataManager.save(cfg);
        notifications.create("Đã lưu cấu hình cho " + currentTableFqn)
                .withType(Notifications.Type.SUCCESS).show();
    }

    @Subscribe("cancelBtn")
    public void onCancelBtnClick(ClickEvent<Button> event) {
        // Hủy thay đổi hiện tại trên UI, không đụng DB
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

    public String getCurrentSelectionJson() {
        return currentSelectionJson;
    }
}
