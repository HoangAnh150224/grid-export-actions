package com.gridexportactions.view.user;

import com.gridexportactions.entity.User;
import com.gridexportactions.view.main.MainView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.data.provider.SortDirection;
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
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
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

    @ViewComponent private DataGrid<User> usersDataGrid;
    @ViewComponent("usersDataGrid.excelExport") private ExcelExportAction excelExport;
    @ViewComponent("usersDc") private CollectionContainer<User> usersDc;

    @Autowired private Downloader downloader;
    @Autowired private DataManager dataManager;
    @Autowired private Notifications notifications;
    @Autowired private ResourceLoader resourceLoader;

    @Value("${app.templates.users:file:./app-templates/users-template.xlsx}")
    private String usersTemplatePath;

    @Value("${app.templates.users.anchorName:in}")
    private String usersTemplateAnchorName;

    // Provider cho cột gộp/custom
    private final Map<String, Function<User, String>> exportValueProviders = new HashMap<>();

    // Cache thứ tự cột đúng như UI (cập nhật khi kéo-thả)
    private List<Grid.Column<User>> uiOrderedColumns = new ArrayList<>();

    // ===== Lifecycle =====
    @Subscribe
    public void onReady(ReadyEvent e) {
        // Lấy thứ tự cột ban đầu theo UI hiện tại
        uiOrderedColumns = new ArrayList<>(usersDataGrid.getColumns());

        // Khi kéo-thả, cập nhật lại cache thứ tự
        usersDataGrid.addColumnReorderListener(ev -> {
            uiOrderedColumns = ev.getColumns(); // thứ tự mới
        });
    }

    private List<Grid.Column<User>> getVisibleColumnsInUiOrder() {
        List<Grid.Column<User>> src = (uiOrderedColumns == null || uiOrderedColumns.isEmpty())
                ? usersDataGrid.getColumns()
                : uiOrderedColumns;
        // Lấy đúng cột đang hiển thị theo thứ tự cache
        return src.stream().filter(Grid.Column::isVisible).collect(Collectors.toList());
    }

    // ===== Named Range helpers =====
    private static class Anchor {
        final Sheet sheet; final int row; final int col;
        Anchor(Sheet s, int r, int c) { sheet = s; row = r; col = c; }
    }

    private static Anchor resolveAnchor(Workbook wb, String name) {
        Name found = null;
        try { found = wb.getName(name); } catch (UnsupportedOperationException ignore) {}
        if (found == null) {
            try {
                for (Name n : wb.getAllNames()) {
                    if (name.equalsIgnoreCase(n.getNameName())) { found = n; break; }
                }
            } catch (UnsupportedOperationException ignored) {}
        }
        if (found == null || found.getRefersToFormula() == null) return null;

        AreaReference ar = new AreaReference(found.getRefersToFormula(), wb.getSpreadsheetVersion());
        CellReference first = ar.getFirstCell();
        Sheet sheet;
        if (first.getSheetName() != null) {
            sheet = wb.getSheet(first.getSheetName());
        } else if (found.getSheetIndex() >= 0) {
            sheet = wb.getSheetAt(found.getSheetIndex());
        } else {
            sheet = wb.getSheetAt(0);
        }
        return new Anchor(sheet, first.getRow(), first.getCol());
    }

    // ===== Gộp cột động =====
    @Subscribe("mergeColumnsBtn")
    public void onMergeColumnsBtnClick(ClickEvent<Button> event) {
        Dialog dlg = new Dialog();
        dlg.setHeaderTitle("Gộp cột");

        List<String> availableKeys = usersDataGrid.getColumns().stream()
                .map(Grid.Column::getKey).filter(Objects::nonNull).collect(Collectors.toList());

        CheckboxGroup<String> pick = new CheckboxGroup<>();
        pick.setLabel("Chọn các cột để gộp (theo thứ tự chọn)");
        pick.setItems(availableKeys);
        pick.select("firstName", "lastName");

        TextField header = new TextField("Tên cột mới");
        header.setValue("Full name");

        String fixedDelimiter = " ";

        Button create = new Button("Tạo cột", e -> {
            List<String> selected = new ArrayList<>(pick.getSelectedItems());
            if (selected.size() < 2) { dlg.close(); return; }

            String newKey = "merged_" + System.currentTimeMillis();

            Grid.Column<User> col = usersDataGrid.addColumn(u -> joinByKeys(u, selected, fixedDelimiter));
            col.setKey(newKey);
            col.setHeader(header.getValue());
            col.setAutoWidth(true);
            col.setVisible(true);

            excelExport.addColumnValueProvider(newKey, ctx -> joinByKeys((User) ctx.getEntity(), selected, fixedDelimiter));
            exportValueProviders.put(newKey, u -> joinByKeys(u, selected, fixedDelimiter));

            // cập nhật lại cache sau khi thêm cột mới
            uiOrderedColumns = new ArrayList<>(usersDataGrid.getColumns());

            dlg.close();
        });

        dlg.add(pick, header, create);
        dlg.open();
    }

    // ===== Xuất theo template (y hệt UI) + thêm cột STT "#" =====
    @Subscribe("exportByTemplateBtn")
    public void onExportByTemplateBtnClick(ClickEvent<Button> event) {
        Resource res = resourceLoader.getResource(usersTemplatePath);
        if (!res.exists()) {
            notifications.create("Không tìm thấy template: " + usersTemplatePath)
                    .withType(Notifications.Type.ERROR).show();
            return;
        }

        try (InputStream is = res.getInputStream();
             Workbook wb = WorkbookFactory.create(is);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            Anchor anchor = resolveAnchor(wb, usersTemplateAnchorName);
            if (anchor == null) {
                notifications.create("Không tìm thấy Named Range: " + usersTemplateAnchorName)
                        .withType(Notifications.Type.ERROR).show();
                return;
            }
            Sheet sheet = anchor.sheet;
            int startRow = anchor.row;
            int startCol = anchor.col;

            // Cột theo đúng thứ tự kéo-thả & đang hiển thị
            List<Grid.Column<User>> visibleCols = getVisibleColumnsInUiOrder();

            // === Ghi header tại hàng anchor ===
            Row headerRow = getOrCreateRow(sheet, startRow);
            CellStyle defaultHeaderStyle = buildDefaultHeaderStyle(wb);

            // 0) CỘT STT "#": đặt ở đúng cột anchor
            {
                // ưu tiên style có sẵn tại ô anchor
                Cell templateSameCell = headerRow.getCell(startCol);
                CellStyle styleToUse = (hasCustomStyle(templateSameCell))
                        ? templateSameCell.getCellStyle()
                        : null;

                // nếu không có, thử lấy ở ô bên trái anchor (ô mẫu)
                if (styleToUse == null && startCol > 0) {
                    Cell leftOfAnchor = headerRow.getCell(startCol - 1);
                    if (hasCustomStyle(leftOfAnchor)) {
                        styleToUse = leftOfAnchor.getCellStyle();
                    }
                }

                if (styleToUse == null) styleToUse = defaultHeaderStyle;

                Cell sttHeader = getOrCreateCell(headerRow, startCol);
                sttHeader.setCellValue("#");
                sttHeader.setCellStyle(styleToUse);
            }

            // 1) Các header còn lại: dịch sang phải 1 cột
            for (int c = 0; c < visibleCols.size(); c++) {
                int colIndex = startCol + 1 + c; // +1 vì cột 0 là STT

                // ưu tiên style đang có sẵn ở đúng ô
                Cell templateSameCell = headerRow.getCell(colIndex);
                CellStyle styleToUse = (hasCustomStyle(templateSameCell))
                        ? templateSameCell.getCellStyle()
                        : null;

                // nếu chưa có, copy style từ cột liền trước (tức là từ cột vừa ghi: có thể là STT hoặc cột trước đó)
                if (styleToUse == null) {
                    Cell prevCell = headerRow.getCell(colIndex - 1);
                    if (hasCustomStyle(prevCell)) {
                        styleToUse = prevCell.getCellStyle();
                    }
                }

                // fallback cuối
                if (styleToUse == null) {
                    styleToUse = defaultHeaderStyle;
                }

                Cell out = getOrCreateCell(headerRow, colIndex);
                out.setCellValue(headerTextFromUI(visibleCols.get(c)));
                out.setCellStyle(styleToUse);
            }

            // dữ liệu ở hàng kế
            startRow++;

            // Lấy toàn bộ dữ liệu (không order DB để giữ sort UI)
            List<User> allUsers = dataManager.load(User.class)
                    .query("select e from User e")
                    .fetchPlan("_base")
                    .list();

            // Áp dụng sort đang bật trên Grid
            Comparator<User> cmp = buildComparatorFromGridSort(usersDataGrid.getSortOrder());
            if (cmp != null) allUsers.sort(cmp);

            // === Ghi dữ liệu ===
            int rowIdx = startRow;
            int stt = 1; // bắt đầu từ 1
            for (User u : allUsers) {
                Row row = getOrCreateRow(sheet, rowIdx);

                // 0) Ghi STT tại cột anchor
                setCellNumber(row, startCol, stt);

                // 1) Ghi các cột dữ liệu dịch sang phải 1 cột
                for (int c = 0; c < visibleCols.size(); c++) {
                    Grid.Column<User> col = visibleCols.get(c);
                    setCellString(row, startCol + 1 + c, valueFor(u, col));
                }

                stt++;
                rowIdx++;
            }

            wb.write(bos);
            downloader.download(bos.toByteArray(), "users.xlsx", DownloadFormat.XLSX);

        } catch (Exception ex) {
            notifications.create("Xuất theo template lỗi: " + ex.getMessage())
                    .withType(Notifications.Type.ERROR).show();
        }
    }

    // ===== Upload template =====
    @Subscribe("uploadTemplateBtn")
    public void onUploadTemplateBtnClick(ClickEvent<Button> event) {
        if (!usersTemplatePath.startsWith("file:")) {
            notifications.create(
                            "Đường dẫn template hiện tại không ghi đè được: " + usersTemplatePath +
                                    "\nHãy đặt app.templates.users về dạng file:, ví dụ: file:./app-templates/users-template.xlsx")
                    .withType(Notifications.Type.WARNING).show();
            return;
        }
        Dialog dlg = new Dialog();
        dlg.setHeaderTitle("Tải lên template (.xlsx)");

        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(".xlsx");
        upload.addSucceededListener(succ -> {
            try (InputStream in = buffer.getInputStream()) {
                Resource r = resourceLoader.getResource(usersTemplatePath);
                File target = r.getFile();
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);

                notifications.create("Đã cập nhật template: " + target.getAbsolutePath())
                        .withType(Notifications.Type.SUCCESS).show();
                dlg.close();
            } catch (Exception ex) {
                notifications.create("Tải template lỗi: " + ex.getMessage())
                        .withType(Notifications.Type.ERROR).show();
            }
        });

        dlg.add(upload);
        dlg.open();
    }

    // ===== Sort helper theo Grid =====
    private Comparator<User> buildComparatorFromGridSort(List<GridSortOrder<User>> orders) {
        if (orders == null || orders.isEmpty()) return null;
        Comparator<User> combined = null;
        for (GridSortOrder<User> so : orders) {
            Grid.Column<User> col = so.getSorted();
            if (col == null) continue;

            Comparator<User> c = Comparator.comparing(
                    u -> {
                        String v = valueFor(u, col);
                        return v == null ? "" : v.toLowerCase(Locale.ROOT);
                    }
            );
            if (so.getDirection() == SortDirection.DESCENDING) c = c.reversed();
            combined = (combined == null) ? c : combined.thenComparing(c);
        }
        return combined;
    }

    // ===== Common helpers =====
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

    private static Cell getOrCreateCell(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) cell = row.createCell(colIndex);
        return cell;
    }

    private static boolean hasCustomStyle(Cell cell) {
        if (cell == null) return false;
        CellStyle st = cell.getCellStyle();
        return st != null && st.getIndex() != 0;
    }

    private static CellStyle buildDefaultHeaderStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());

        CellStyle st = wb.createCellStyle();
        st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        st.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        st.setAlignment(HorizontalAlignment.CENTER);
        st.setVerticalAlignment(VerticalAlignment.CENTER);
        st.setBorderBottom(BorderStyle.THIN);
        st.setBottomBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        st.setFont(font);
        return st;
    }

    private static void setCellString(Row row, int colIndex, String val) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) cell = row.createCell(colIndex);
        cell.setCellValue(val == null ? "" : val);
    }

    private static void setCellNumber(Row row, int colIndex, double val) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) cell = row.createCell(colIndex);
        cell.setCellValue(val);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String headerTextFromUI(Grid.Column<?> col) {
        try {
            var m = col.getClass().getMethod("getHeaderText");
            Object o = m.invoke(col);
            if (o instanceof String s && !s.isBlank()) return s;
        } catch (Exception ignored) {}
        try {
            var m = col.getClass().getMethod("getHeader");
            Object comp = m.invoke(col);
            if (comp instanceof HasText ht && ht.getText() != null && !ht.getText().isBlank())
                return ht.getText();
        } catch (Exception ignored) {}
        try {
            var m2 = col.getClass().getMethod("getHeaderComponent");
            Object comp2 = m2.invoke(col);
            if (comp2 instanceof HasText ht2 && ht2.getText() != null && !ht2.getText().isBlank())
                return ht2.getText();
        } catch (Exception ignored) {}
        String k = col.getKey();
        return k == null ? "" : k;
    }

    private String valueFor(User u, Grid.Column<User> col) {
        String key = col.getKey();
        if (key == null) return "";

        // cột custom/gộp
        Function<User, String> fn = exportValueProviders.get(key);
        if (fn != null) return safe(fn.apply(u));

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

    private static String safe(String s) { return s == null ? "" : s; }
}
