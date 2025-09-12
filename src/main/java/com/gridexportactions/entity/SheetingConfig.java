package com.gridexportactions.entity;

import io.jmix.core.FileRef;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@JmixEntity(name = "SheetingConfig")
@Entity(name = "SheetingConfig")
@Table(name = "SHEETING_CONFIG")
public class SheetingConfig {

    @Id
    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "TABLE_NAME", nullable = false, length = 255)
    private String tableName;

    @Lob
    @NotNull
    @Column(name = "COLUMNS_JSON", nullable = false)
    private String columnsJson;

    @Column(name = "TEMPLATE_REF", length = 1024)
    private FileRef templateRef;

    public FileRef getTemplateRef() {
        return templateRef;
    }
    public void setTemplateRef(FileRef templateRef) {
        this.templateRef = templateRef;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getColumnsJson() { return columnsJson; }
    public void setColumnsJson(String columnsJson) { this.columnsJson = columnsJson; }

    @InstanceName
    public String getInstanceName() { return tableName; }
}
