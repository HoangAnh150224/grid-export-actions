package com.gridexportactions.view.militarywarehouse;

import com.gridexportactions.entity.MilitaryWarehouse;
import com.gridexportactions.view.main.MainView;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "military-warehouses/:id", layout = MainView.class)
@ViewController(id = "MilitaryWarehouse.detail")
@ViewDescriptor(path = "military-warehouse-detail-view.xml")
@EditedEntityContainer("militaryWarehouseDc")
public class MilitaryWarehouseDetailView extends StandardDetailView<MilitaryWarehouse> {
}