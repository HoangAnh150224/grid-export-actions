package com.gridexportactions.view.militarywarehouse;

import com.gridexportactions.entity.MilitaryWarehouse;
import com.gridexportactions.view.main.MainView;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.view.*;
import com.vaadin.flow.router.Route;

@Route(value = "military-warehouses", layout = MainView.class)
@ViewController(id = "MilitaryWarehouse.list")
@ViewDescriptor(path = "military-warehouse-list-view.xml")
@LookupComponent("militaryWarehousesDataGrid")
@DialogMode(width = "64em")
public class MilitaryWarehouseListView extends StandardListView<MilitaryWarehouse> {

    @ViewComponent
    private DataGrid<MilitaryWarehouse> militaryWarehousesDataGrid;

    @ViewComponent("militaryWarehousesDc")
    private CollectionContainer<MilitaryWarehouse> militaryWarehousesDc;
}
