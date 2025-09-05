package com.gridexportactions.view.user;

import com.gridexportactions.entity.User;
import com.gridexportactions.view.main.MainView;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.view.*;
import io.jmix.gridexportflowui.action.ExcelExportAction;
import com.vaadin.flow.component.ClickEvent;


import java.util.*;
import java.util.stream.Collectors;

@Route(value = "users", layout = MainView.class)
@ViewController(id = "User.list")
@ViewDescriptor(path = "user-list-view.xml")
@LookupComponent("usersDataGrid")
@DialogMode(width = "64em")
public class UserListView extends StandardListView<User> {

    @ViewComponent
    private DataGrid<User> usersDataGrid;

    @ViewComponent("usersDataGrid.excelExport")
    private ExcelExportAction excelExport;

    @Subscribe("mergeColumnsBtn")
    public void onMergeColumnsBtnClick(ClickEvent<Button> event) {
        Dialog dlg = new Dialog();
        dlg.setHeaderTitle("Gộp cột");

        List<String> availableKeys = usersDataGrid.getColumns().stream()
                .map(Grid.Column::getKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        CheckboxGroup<String> pick = new CheckboxGroup<>();
        pick.setLabel("Chọn các cột để gộp (theo thứ tự chọn)");
        pick.setItems(availableKeys);
        pick.select("firstName", "lastName");

        TextField header = new TextField("Tên cột mới");
        header.setValue("Full name");

        String fixedDelimiter = " "; // đặt sẵn

        Button create = new Button("Tạo cột", e -> {
            List<String> selected = new ArrayList<>(pick.getSelectedItems());
            if (selected.size() < 2) {
                dlg.close();
                return;
            }

            String newKey = "merged_" + System.currentTimeMillis();

            Grid.Column<User> col = usersDataGrid.addColumn(u ->
                    joinByKeys(u, selected, fixedDelimiter));
            col.setKey(newKey);
            col.setHeader(header.getValue());
            col.setAutoWidth(true);
            col.setVisible(true);

            excelExport.addColumnValueProvider(newKey, ctx ->
                    joinByKeys((User) ctx.getEntity(), selected, fixedDelimiter));

            dlg.close();
        });

        dlg.add(pick, header, create); // bỏ delimiter ra
        dlg.open();
    }


    private static String joinByKeys(User u, List<String> keys, String delim) {
        String d = (delim == null ? " " : delim);
        return keys.stream()
                .map(k -> switch (k) { // đọc giá trị theo key cột
                    case "firstName" -> nz(u.getFirstName());
                    case "lastName" -> nz(u.getLastName());
                    case "username" -> nz(u.getUsername());
                    case "email" -> nz(u.getEmail());
                    case "timeZoneId" -> (u.getTimeZoneId() == null ? "" : u.getTimeZoneId());
                    case "active" -> String.valueOf(u.getActive());
                    default -> "";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(d))
                .trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
