package com.gridexportactions.sheeting;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import io.jmix.core.entity.EntityValues;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.action.list.ListDataComponentAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.EnhancedDataGrid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** Gộp nhiều cột thành một cột ảo (tạm thời), dùng chung cho mọi DataGrid. */
@ActionType("grid_mergeColumn")
@Component("grid_MergeColumnAction")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class GridMergeColumnAction extends ListDataComponentAction<GridMergeColumnAction, Object> {

    @Autowired
    private Notifications notify;

    public GridMergeColumnAction() { this("grid_mergeColumn"); }
    public GridMergeColumnAction(String id) { super(id); }

    @Override
    public void execute() { actionPerform(null); }

    @Override
    public void actionPerform(com.vaadin.flow.component.Component ignored) {
        var target = getTarget();
        if (!(target instanceof DataGrid<?> dg)) return;

        var cols = listCols(dg);
        if (cols.isEmpty()) { toast(Notifications.Type.WARNING, "Không có cột hợp lệ để gộp."); return; }

        var dlg = new Dialog();
        dlg.setHeaderTitle("Gộp cột (tạm thời)");

        var pick = new CheckboxGroup<Col>();
        pick.setLabel("Chọn cột (giữ thứ tự)");
        pick.setItems(cols);
        pick.setItemLabelGenerator(Col::label);

        var ordered = new LinkedHashSet<Col>();
        pick.addValueChangeListener(e -> {
            ordered.clear();
            var sel = e.getValue() == null ? Set.<Col>of() : e.getValue();
            for (var c : cols) if (sel.contains(c)) ordered.add(c);
        });

        if (cols.size() >= 2) pick.setValue(new LinkedHashSet<>(List.of(cols.get(0), cols.get(1))));

        var title = new TextField("Tiêu đề");
        title.setValue("Cột gộp");
        var sep = new TextField("Dấu ngăn cách");
        sep.setValue(" ");

        var apply = new Button("Thêm cột ảo", e -> {
            if (addMergeCol(dg, ordered, title.getValue(), sep.getValue())) dlg.close();
        });
        var close = new Button("Đóng", e -> dlg.close());

        var btns = new HorizontalLayout(apply, close);
        btns.getStyle().set("margin-top", "0.5rem");

        var content = new Div(pick, title, sep, btns);
        content.getStyle().set("min-width", "28rem");
        dlg.add(content);
        dlg.open();
    }

    /* ================= helpers ================= */

    private List<Col> listCols(DataGrid<?> grid) {
        var edg = (EnhancedDataGrid) grid;
        return ((DataGrid<Object>) grid).getAllColumns().stream()
                .map(c -> {
                    var mpp = edg.getColumnMetaPropertyPath(c);
                    if (mpp == null) return null;
                    var path = mpp.toPathString();
                    var header = Optional.ofNullable(c.getHeaderText()).filter(s -> !s.isBlank()).orElse(path);
                    return new Col(path, header);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private boolean addMergeCol(DataGrid<?> grid, Collection<Col> ordered, String header, String sep) {
        var paths = ordered.stream().map(Col::path).filter(p -> p != null && !p.isBlank()).toList();
        if (paths.size() < 2) { toast(Notifications.Type.WARNING, "Chọn ít nhất 2 cột."); return false; }

        var delimiter = isBlank(sep) ? " " : sep;
        var key = "__mergeCol_" + Integer.toHexString(Objects.hash(paths, delimiter));

        var exists = ((DataGrid<Object>) grid).getAllColumns().stream().anyMatch(c -> key.equals(c.getKey()));
        if (exists) { toast(Notifications.Type.DEFAULT, "Cột gộp này đã tồn tại."); return true; }

        var col = ((DataGrid<Object>) grid).addColumn(item -> render(item, paths, delimiter));
        col.setKey(key);
        col.setHeader(isBlank(header) ? "Cột gộp" : header);
        col.setAutoWidth(true);
        col.setResizable(true);
        col.setVisible(true);

        toast(Notifications.Type.SUCCESS, "Đã thêm cột gộp tạm thời.");
        return true;
    }

    private String render(Object item, List<String> paths, String sep) {
        var parts = new ArrayList<String>(paths.size());
        for (var p : paths) {
            Object v;
            try { v = EntityValues.getValueEx(item, p); }
            catch (Exception ignore) { v = null; }
            if (v != null) {
                var s = String.valueOf(v);
                if (!s.isBlank()) parts.add(s);
            }
        }
        return String.join(sep, parts);
    }

    private void toast(Notifications.Type type, String msg) { notify.create(msg).withType(type).show(); }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private record Col(String path, String label) { @Override public String toString() { return label; } }
}
