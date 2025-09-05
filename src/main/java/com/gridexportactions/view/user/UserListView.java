package com.gridexportactions.view.user;

import com.gridexportactions.entity.User;
import com.gridexportactions.view.main.MainView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.download.DownloadFormat;
import io.jmix.flowui.download.Downloader;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.view.*;
import io.jmix.gridexportflowui.action.ExcelExportAction;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
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

    @ViewComponent("usersDc")
    private CollectionContainer<User> usersDc;

    @Autowired
    private Downloader downloader;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Notifications notifications;

    @Autowired
    private ResourceLoader resourceLoader;

    // Cho phép override qua application.properties
    // ví dụ: app.templates.users=file:./app-templates/users-template.xlsx
    @Value("${app.templates.users:file:./app-templates/users-template.xlsx}")
    private String usersTemplatePath;

    // Provider giá trị khi export theo template (cho cột gộp hoặc cột custom)
    private final Map<String, Function<User, String>> exportValueProviders = new HashMap<>();

    // ================= GỘP CỘT ĐỘNG =================
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

        String fixedDelimiter = " ";

        Button create = new Button("Tạo cột", e -> {
            List<String> selected = new ArrayList<>(pick.getSelectedItems());
            if (selected.size() < 2) {
                dlg.close();
                return;
            }

            String newKey = "merged_" + System.currentTimeMillis();

            // Thêm cột hiển thị trên lưới
            Grid.Column<User> col = usersDataGrid.addColumn(u -> joinByKeys(u, selected, fixedDelimiter));
            col.setKey(newKey);
            col.setHeader(header.getValue());
            col.setAutoWidth(true);
            col.setVisible(true);

            // Cho ExcelExportAction biết cách lấy giá trị
            excelExport.addColumnValueProvider(newKey, ctx ->
                    joinByKeys((User) ctx.getEntity(), selected, fixedDelimiter));

            // Cho template export biết cách lấy giá trị
            exportValueProviders.put(newKey, u -> joinByKeys(u, selected, fixedDelimiter));

            dlg.close();
        });

        dlg.add(pick, header, create);
        dlg.open();
    }

    // ================= XUẤT THEO TEMPLATE (tất cả cột ĐANG HIỂN THỊ & TẤT CẢ USER TRONG DB) =================
    @Subscribe("exportByTemplateBtn")
    public void onExportByTemplateBtnClick(ClickEvent<Button> event) {
        Resource res = resourceLoader.getResource(usersTemplatePath);
        if (!res.exists()) {
            notifications.create("Không tìm thấy template: " + usersTemplatePath)
                    .withType(Notifications.Type.ERROR)
                    .show();
            return;
        }

        try (InputStream is = res.getInputStream();
             Workbook wb = WorkbookFactory.create(is);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            Sheet sheet = wb.getSheetAt(0);

            // LẤY CỘT ĐANG HIỂN THỊ (thứ tự như trên UI)
            List<Grid.Column<User>> visibleCols = usersDataGrid.getColumns().stream()
                    .filter(Grid.Column::isVisible)
                    .collect(Collectors.toList());

            // (Tuỳ chọn) Ghi header lên template - nếu muốn giữ header gốc thì bỏ khối dưới
            Row headerRow = getOrCreateRow(sheet, 0);
            for (int c = 0; c < visibleCols.size(); c++) {
                Grid.Column<User> col = visibleCols.get(c);
                setCellString(headerRow, c, headerText(col));
            }

            // >>> TẢI TẤT CẢ USER TỪ DB (bỏ qua phân trang/UI) <<<
            List<User> allUsers = dataManager.load(User.class)
                    .query("select e from User e order by e.username")
                    .fetchPlan("_base")
                    .list();

            // Ghi dữ liệu bắt đầu từ hàng 6 (index 5) — chỉnh theo mẫu của bạn
            int rowIdx = 5;
            for (User u : allUsers) {
                Row row = getOrCreateRow(sheet, rowIdx);
                for (int c = 0; c < visibleCols.size(); c++) {
                    Grid.Column<User> col = visibleCols.get(c);
                    String value = valueFor(u, col); // dùng provider cho cột gộp nếu có
                    setCellString(row, c, value);
                }
                rowIdx++;
            }

            wb.write(bos);
            downloader.download(bos.toByteArray(), "users.xlsx", DownloadFormat.XLSX);

        } catch (Exception ex) {
            notifications.create("Xuất theo template lỗi: " + ex.getMessage())
                    .withType(Notifications.Type.ERROR)
                    .show();
        }
    }

    // ================= TẢI (UPLOAD) TEMPLATE =================
    @Subscribe("uploadTemplateBtn")
    public void onUploadTemplateBtnClick(ClickEvent<Button> event) {
        // Chỉ cho phép ghi đè khi đường dẫn là dạng "file:"
        if (!usersTemplatePath.startsWith("file:")) {
            notifications.create(
                            "Đường dẫn template hiện tại không ghi đè được: " + usersTemplatePath +
                                    "\nHãy đặt app.templates.users về dạng file:, ví dụ: file:./app-templates/users-template.xlsx")
                    .withType(Notifications.Type.WARNING)
                    .show();
            return;
        }

        Dialog dlg = new Dialog();
        dlg.setHeaderTitle("Tải lên template (.xlsx)");

        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(".xlsx");
        upload.addSucceededListener(succ -> {
            try (InputStream in = buffer.getInputStream()) {
                Resource res = resourceLoader.getResource(usersTemplatePath);
                File target = res.getFile(); // với file:… luôn trả về File, kể cả khi chưa tồn tại
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);

                notifications.create("Đã cập nhật template: " + target.getAbsolutePath())
                        .withType(Notifications.Type.SUCCESS)
                        .show();
                dlg.close();
            } catch (Exception ex) {
                notifications.create("Tải template lỗi: " + ex.getMessage())
                        .withType(Notifications.Type.ERROR)
                        .show();
            }
        });

        dlg.add(upload);
        dlg.open();
    }

    // ================= Helpers =================
    private static String joinByKeys(User u, List<String> keys, String delim) {
        String d = (delim == null ? " " : delim);
        return keys.stream()
                .map(k -> switch (k) {
                    case "firstName" -> nz(u.getFirstName());
                    case "lastName"  -> nz(u.getLastName());
                    case "username"  -> nz(u.getUsername());
                    case "email"     -> nz(u.getEmail());
                    case "timeZoneId"-> (u.getTimeZoneId() == null ? "" : u.getTimeZoneId());
                    case "active"    -> String.valueOf(u.getActive());
                    default          -> "";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(d))
                .trim();
    }

    private static Row getOrCreateRow(Sheet sheet, int rowIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        return row;
    }

    private static void setCellString(Row row, int colIndex, String val) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) cell = row.createCell(colIndex);
        cell.setCellValue(val == null ? "" : val);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String headerText(Grid.Column<User> col) {
        // Không có API header getter chuẩn -> dùng key/cột
        String k = col.getKey();
        return k == null ? "" : k;
    }

    private String valueFor(User u, Grid.Column<User> col) {
        String key = col.getKey();
        if (key == null) return "";

        // Ưu tiên provider custom (cột gộp,…)
        Function<User, String> fn = exportValueProviders.get(key);
        if (fn != null) return safe(fn.apply(u));

        // Các cột property mặc định trong XML
        return switch (key) {
            case "username"   -> nz(u.getUsername());
            case "firstName"  -> nz(u.getFirstName());
            case "lastName"   -> nz(u.getLastName());
            case "email"      -> nz(u.getEmail());
            case "timeZoneId" -> u.getTimeZoneId() == null ? "" : u.getTimeZoneId();
            case "active"     -> u.getActive() == null ? "" : String.valueOf(u.getActive());
            default           -> "";
        };
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
