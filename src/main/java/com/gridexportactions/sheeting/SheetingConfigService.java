package com.gridexportactions.sheeting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridexportactions.entity.SheetingConfig;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.flowui.component.ListDataComponent;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.EnhancedDataGrid;
import io.jmix.flowui.data.grid.ContainerDataGridItems;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class SheetingConfigService {

    private final DataManager dataManager;
    private final ObjectMapper objectMapper;
    private final Metadata metadata;
    private final MetadataTools metadataTools;

    public SheetingConfigService(DataManager dataManager,
                                 ObjectMapper objectMapper,
                                 Metadata metadata,
                                 MetadataTools metadataTools) {
        this.dataManager = dataManager;
        this.objectMapper = objectMapper;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
    }

    /* ===================== PUBLIC API ===================== */

    /** Tìm cấu hình theo DataGrid (suy bảng) và parse spec */
    public Optional<Spec> findFor(DataGrid<?> grid) {
        Class<?> entityClass = resolveEntityClass(grid);
        if (entityClass == null) return Optional.empty();

        MetaClass mc = metadata.getClass(entityClass);
        List<String> candidates = buildTableCandidates(mc).stream()
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (candidates.isEmpty()) return Optional.empty();

        String where = IntStream.range(0, candidates.size())
                .mapToObj(i -> "lower(e.tableName) = :c" + i)
                .collect(Collectors.joining(" or "));

        var loader = dataManager.load(SheetingConfig.class)
                .query("select e from SheetingConfig e where " + where);

        for (int i = 0; i < candidates.size(); i++) loader.parameter("c" + i, candidates.get(i));

        List<SheetingConfig> list = loader.list();
        if (list.isEmpty()) return Optional.empty();

        SheetingConfig best = list.stream()
                .max(Comparator.comparingInt(cfg -> safe(cfg.getTableName()).length()))
                .orElse(list.get(0));

        return parseSpec(best.getColumnsJson(), best.getTableName());
    }

    /** Ép thứ tự cột DB (snake_case) -> property path trong Grid */
    public List<String> resolvePropertyOrder(DataGrid<?> grid, List<String> dbCols) {
        if (dbCols == null || dbCols.isEmpty()) return List.of();

        Map<String, String> propertyToDbLower = new LinkedHashMap<>();
        var edg = (EnhancedDataGrid) grid;

        for (var col : ((DataGrid<Object>) grid).getAllColumns()) {
            var mpp = edg.getColumnMetaPropertyPath(col);
            if (mpp == null) continue;
            MetaProperty mp = mpp.getMetaProperty();
            String dbName = safeLower(getDatabaseColumnName(mp));
            if (dbName != null && !dbName.isBlank()) {
                propertyToDbLower.put(mpp.toPathString(), dbName);
            }
        }

        Set<String> wanted = dbCols.stream()
                .filter(Objects::nonNull)
                .map(this::safeLower)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> order = new ArrayList<>();
        for (String db : wanted) {
            for (var e : propertyToDbLower.entrySet()) {
                if (Objects.equals(e.getValue(), db) && !order.contains(e.getKey())) {
                    order.add(e.getKey());
                }
            }
        }
        return order;
    }

    /** Map DB column (lowercase) -> property path trong Grid, để lookup nhanh */
    public Map<String, String> buildDbToPropertyPathMap(DataGrid<?> grid) {
        Map<String, String> map = new LinkedHashMap<>();
        var edg = (EnhancedDataGrid) grid;
        for (var col : ((DataGrid<Object>) grid).getAllColumns()) {
            var mpp = edg.getColumnMetaPropertyPath(col);
            if (mpp == null) continue;
            MetaProperty mp = mpp.getMetaProperty();
            String db = safeLower(getDatabaseColumnName(mp));
            if (db != null && !db.isBlank()) map.put(db, mpp.toPathString());
        }
        return map;
    }

    /* ===================== SPEC / JSON ===================== */

    private Optional<Spec> parseSpec(String json, String table) {
        try {
            Map<String, Object> map = objectMapper.readValue(
                    Optional.ofNullable(json).orElse("{}"),
                    new TypeReference<Map<String, Object>>() {}
            );

            Spec s = new Spec();
            s.table = table;
            s.sheetName = Objects.toString(map.getOrDefault("sheetName", "Export"), "Export");
            s.templateHasHeader = Boolean.parseBoolean(
                    Objects.toString(map.getOrDefault("templateHasHeader", "true"))
            );
            s.templateUploaded = objToStr(map.get("templateUploaded"));
            s.headerAnchor = objToStr(map.get("headerAnchor")); // optional
            s.dataAnchor = objToStr(map.get("dataAnchor"));     // optional

            // Parse columns: chấp nhận [{name,tplVar}] hoặc ["colA","colB"]
            Object cols = map.get("columns");
            if (cols instanceof Collection<?> arr) {
                for (Object it : arr) {
                    if (it instanceof Map<?,?> m) {
                        String name = toStr(m.get("name"));
                        if (name.isBlank()) continue;
                        s.columns.add(name);
                        String var = toStr(m.get("tplVar"));
                        if (!var.isBlank()) s.dbToTplVar.put(name.toLowerCase(Locale.ROOT), var);
                    } else if (it != null) {
                        String name = it.toString().trim();
                        if (!name.isBlank()) s.columns.add(name);
                    }
                }
            }

            // Back-compat templateVars {dbCol -> var}
            Object tv = map.get("templateVars");
            if (tv instanceof Map<?,?> tm) {
                for (var e : tm.entrySet()) {
                    String k = toStr(e.getKey()).toLowerCase(Locale.ROOT);
                    String v = toStr(e.getValue());
                    if (!k.isBlank() && !v.isBlank()) s.dbToTplVar.putIfAbsent(k, v);
                }
            }

            // Nếu thiếu columns mà có columnsFlat thì lấp vào
            Object cf = map.get("columnsFlat");
            if (s.columns.isEmpty() && cf instanceof Collection<?> fl) {
                for (Object o : fl) if (o != null) {
                    String n = o.toString().trim();
                    if (!n.isBlank()) s.columns.add(n);
                }
            }

            return Optional.of(s.withResolvedTable(table));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /* ===================== Helpers ===================== */

    private String toStr(Object o) { return o == null ? "" : o.toString().trim(); }
    private String safeLower(String s){ return s==null?null:s.toLowerCase(Locale.ROOT); }
    private String norm(String s){ return s==null?"":s.replace("\"","").trim().toLowerCase(Locale.ROOT); }
    private String emptyToNull(String s){ return (s==null||s.isBlank())?null:s; }
    private String safe(String s){ return s==null?"":s; }

    @SuppressWarnings("unchecked")
    private Class<?> resolveEntityClass(DataGrid<?> grid) {
        if (!(grid instanceof ListDataComponent<?> ldc)) return null;
        if (!(ldc.getItems() instanceof ContainerDataGridItems<?> items)) return null;
        return items.getContainer().getEntityMetaClass().getJavaClass();
    }

    private Set<String> buildTableCandidates(MetaClass mc) {
        Set<String> set = new LinkedHashSet<>();
        String tbl = metadataTools.getDatabaseTable(mc);
        if (tbl != null) set.add(norm(tbl));
        Table ann = mc.getJavaClass().getAnnotation(Table.class);
        if (ann != null) {
            String n = emptyToNull(ann.name());
            String s = emptyToNull(ann.schema());
            if (n != null) set.add(norm(n));
            if (s != null && n != null) set.add(norm(s + "." + n));
            if (s == null && n != null) set.add(norm("public." + n));
        }
        set.addAll(
                set.stream()
                        .map(x -> x.contains(".") ? x.substring(x.indexOf('.') + 1) : x)
                        .map(this::norm).toList()
        );
        return set;
    }

    private String getDatabaseColumnName(MetaProperty mp) {
        MetaClass domainMc = mp.getDomain();
        Class<?> entityJavaClass = (domainMc != null) ? domainMc.getJavaClass() : null;
        String prop = mp.getName();
        if (entityJavaClass == null) return prop;

        Field f = findField(entityJavaClass, prop);
        if (f != null) {
            String n = columnNameFromAnnotations(f.getAnnotation(Column.class), f.getAnnotation(JoinColumn.class));
            if (n != null) return n;
        }
        Method m = findGetter(entityJavaClass, prop);
        if (m != null) {
            String n = columnNameFromAnnotations(m.getAnnotation(Column.class), m.getAnnotation(JoinColumn.class));
            if (n != null) return n;
        }
        return prop;
    }

    private String columnNameFromAnnotations(Column col, JoinColumn joinCol) {
        if (col != null && col.name() != null && !col.name().isBlank()) return col.name();
        if (joinCol != null && joinCol.name() != null && !joinCol.name().isBlank()) return joinCol.name();
        return null;
    }
    private Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignore) {}
            c = c.getSuperclass();
        }
        return null;
    }
    private Method findGetter(Class<?> cls, String prop) {
        String base = prop.substring(0,1).toUpperCase(Locale.ROOT) + prop.substring(1);
        for (String n : new String[]{"get"+base, "is"+base}) {
            try { return cls.getMethod(n); } catch (NoSuchMethodException ignore) {}
        }
        return null;
    }

    /* ===================== DTO ===================== */

    public static class Spec {
        public String table;
        public List<String> columns = new ArrayList<>();
        public String sheetName = "Export";

        // Template + anchors (tên file nằm ở ./app-templates)
        public String headerAnchor;          // optional
        public String dataAnchor;            // optional
        public boolean templateHasHeader = true;
        public String templateUploaded;      // file template

        // mapping DB col -> tplVar
        public Map<String,String> dbToTplVar = new LinkedHashMap<>();

        public Spec withResolvedTable(String t){ this.table=t; return this; }

        public boolean hasTemplate() {
            return templateUploaded != null && !templateUploaded.isBlank();
        }
    }

    private static String objToStr(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
