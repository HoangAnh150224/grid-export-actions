package com.gridexportactions.view.export;

import com.vaadin.flow.component.grid.Grid;
import io.jmix.core.DateTimeTransformations;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.Notifications;
import io.jmix.gridexportflowui.GridExportProperties;
import io.jmix.gridexportflowui.exporter.excel.ExcelExporter;

import java.util.*;
import java.util.function.Predicate;
import java.util.LinkedHashMap;

/** Exporter fallback (không template). Chỉ ép thứ tự cột qua getColumns. */
public class OffsetExcelExporter extends ExcelExporter {

    private List<String> propertyOrder = List.of();

    public OffsetExcelExporter(GridExportProperties gridExportProperties,
                               Notifications notifications,
                               io.jmix.gridexportflowui.exporter.entitiesloader.AllEntitiesLoaderFactory allEntitiesLoaderFactory,
                               CurrentAuthentication currentAuthentication,
                               DateTimeTransformations dateTimeTransformations) {
        super(gridExportProperties, notifications, allEntitiesLoaderFactory, currentAuthentication, dateTimeTransformations);
    }

    /** Ép thứ tự theo property path (ví dụ: "warehouse.name", "status"). */
    public OffsetExcelExporter withPropertyOrder(List<String> order) {
        this.propertyOrder = (order == null) ? List.of() : List.copyOf(order);
        return this;
    }

    /** Giữ nguyên exportDataGrid của superclass. Chỉ reorder cột ở đây. */
    @Override
    protected List<Grid.Column<Object>> getColumns(Grid<Object> dataGrid,
                                                   Predicate<Grid.Column<Object>> columnFilter) {
        List<Grid.Column<Object>> cols = super.getColumns(dataGrid, columnFilter);
        if (propertyOrder == null || propertyOrder.isEmpty()) return cols;

        var edg = (io.jmix.flowui.component.grid.EnhancedDataGrid) dataGrid;

        // Map propertyPath -> column
        Map<String, Grid.Column<Object>> byProp = new LinkedHashMap<>();
        for (var c : cols) {
            var mpp = edg.getColumnMetaPropertyPath(c);
            if (mpp != null) {
                byProp.put(mpp.toPathString(), c);
            }
        }

        // Lấy theo order đã chỉ định, sau đó append những cột còn lại
        List<Grid.Column<Object>> ordered = new ArrayList<>();
        for (String p : propertyOrder) {
            var c = byProp.get(p);
            if (c != null && !ordered.contains(c)) ordered.add(c);
        }
        for (var c : cols) {
            if (!ordered.contains(c)) ordered.add(c);
        }
        return ordered;
    }
}
