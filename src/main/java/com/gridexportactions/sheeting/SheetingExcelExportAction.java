package com.gridexportactions.sheeting;

import com.gridexportactions.view.export.OffsetExcelExporter;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.action.list.ListDataComponentAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.download.Downloader;
import io.jmix.gridexportflowui.exporter.ExportMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

@ActionType("sheeting_excelExport")
@Component("sheeting_ExcelExportAction")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class SheetingExcelExportAction extends ListDataComponentAction<SheetingExcelExportAction, Object> {

    @Autowired private SheetingConfigService configService;
    @Autowired private Downloader downloader;
    @Autowired private AutowireCapableBeanFactory beanFactory;
    @Autowired private FileStorage fileStorage;

    public SheetingExcelExportAction() { this("sheeting_excelExport"); }
    public SheetingExcelExportAction(String id) { super(id); }

    @Override public void execute() { actionPerform(null); }

    @Override
    public void actionPerform(com.vaadin.flow.component.Component ignored) {
        var target = getTarget();
        if (!(target instanceof DataGrid<?>)) return;

        @SuppressWarnings("unchecked")
        DataGrid<Object> grid = (DataGrid<Object>) target;

        OffsetExcelExporter exporter = beanFactory.createBean(OffsetExcelExporter.class);

        Optional<SheetingConfigService.Spec> specOpt = configService.findFor(grid);
        if (specOpt.isEmpty()) {
            // Không có cấu hình -> xuất theo cột đang hiển thị
            exporter.exportDataGrid(downloader, grid, ExportMode.ALL_ROWS, c -> c.isVisible());
            return;
        }

        var spec = specOpt.get();

        // 1) Áp cột ảo (nếu có)
        configService.applyVirtualColumns(grid, spec);

        // 2) Ép thứ tự property theo columns từ DB
        List<String> propertyOrder = configService.resolvePropertyOrder(grid, spec.columns);

        // 3) Filter: chỉ các cột trong order + cho phép cột ảo (ValueProvider)
        Predicate<DataGrid.Column<Object>> filter =
                configService.buildColumnFilter(grid, propertyOrder, true);

        // 4) Nếu có template hợp lệ -> đọc bytes và đổ theo Named Range
        byte[] templateBytes = tryLoadTemplate(spec);
        if (templateBytes != null) {
            exporter.withTemplate(templateBytes, spec.headerAnchor, spec.dataAnchor, spec.templateHasHeader)
                    .withPropertyOrder(propertyOrder)
                    .exportDataGrid(downloader, grid, ExportMode.ALL_ROWS, filter);
            return;
        }

        // 5) Không có template -> xuất mặc định (tôn trọng propertyOrder)
        exporter.withPropertyOrder(propertyOrder)
                .exportDataGrid(downloader, grid, ExportMode.ALL_ROWS, filter);
    }

    private byte[] tryLoadTemplate(SheetingConfigService.Spec spec) {
        if (!spec.hasTemplateRef()) return null;
        try {
            FileRef ref = new FileRef(spec.templateStorage, spec.templateFileName, spec.templateFileId);
            try (InputStream is = fileStorage.openStream(ref)) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            // Không mở được template -> fallback mặc định
            return null;
        }
    }
}
