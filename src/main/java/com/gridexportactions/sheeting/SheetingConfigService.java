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
import java.util.function.Function;
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

        // Tránh lỗi JPQL parser với IN(lower(...)). Xây where dạng OR.
        String where = IntStream.range(0, candidates.size())
                .mapToObj(i -> "lower(e.tableName) = :c" + i)
                .collect(Collectors.joining(" or "));

        var loader = dataManager.load(SheetingConfig.class)
                .query("select e from SheetingConfig e where " + where);

        for (int i = 0; i < candidates.size(); i++) {
            loader.parameter("c" + i, candidates.get(i));
        }

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

    /** Filter cột theo propertyOrder; cho phép cả cột ảo nếu includeValueProviderCols=true */
    public java.util.function.Predicate<DataGrid.Column<Object>> buildColumnFilter(
            DataGrid<?> grid, List<String> propertyOrder, boolean includeValueProviderCols) {
        Set<String> allow = new HashSet<>(propertyOrder);
        var edg = (EnhancedDataGrid) grid;
        return c -> {
            var mpp = edg.getColumnMetaPropertyPath(c);
            if (mpp == null) {
                return includeValueProviderCols && c.isVisible();
            }
            return allow.contains(mpp.toPathString());
        };
    }

    // Bản rút gọn giữ tương thích chỗ cũ
    public java.util.function.Predicate<DataGrid.Column<Object>> buildColumnFilter(
            DataGrid<?> grid, List<String> propertyOrder) {
        return buildColumnFilter(grid, propertyOrder, false);
    }

    /** Áp các "virtualColumns" từ spec vào Grid (chỉ thêm 1 lần) */
    public void applyVirtualColumns(DataGrid<?> grid, Spec spec) {
        if (spec == null || spec.virtualColumns.isEmpty()) return;

        // Map DB column -> property path
        Map<String, String> dbToProperty = buildDbToPropertyPathMap(grid);

        for (VirtualColumn vc : spec.virtualColumns) {
            String key = "virt:" + vc.key;
            boolean exists = grid.getAllColumns().stream().anyMatch(c -> key.equals(c.getKey()));
            if (exists) continue;

            // Chuẩn bị danh sách property path tương ứng concatOf
            List<String> propPaths = new ArrayList<>();
            for (String db : vc.concatOf) {
                String pp = dbToProperty.get(safeLower(db));
                if (pp != null) propPaths.add(pp);
            }
            if (propPaths.isEmpty()) continue;

            // ValueProvider đọc entity theo property path rồi ghép chuỗi
            Function<Object, String> provider = entity -> {
                List<String> parts = new ArrayList<>();
                Object cur = entity;
                for (String path : propPaths) {
                    Object v = readByPath(cur, path);
                    parts.add(v == null ? "" : String.valueOf(v));
                }
                return parts.stream().filter(s -> !s.isBlank()).collect(Collectors.joining(vc.delimiter));
            };

            @SuppressWarnings("unchecked")
            DataGrid.Column<Object> newCol = ((DataGrid<Object>) grid).addColumn(provider::apply);
            newCol.setKey(key);
            newCol.setHeader(vc.header == null ? key : vc.header);
            newCol.setAutoWidth(true);
            newCol.setVisible(true);
        }
    }

    /* ===================== JSON / SPEC ===================== */

    private Optional<Spec> parseSpec(String json, String table) {
        try {
            Map<String, Object> map = objectMapper.readValue(
                    Optional.ofNullable(json).orElse("{}"),
                    new TypeReference<Map<String, Object>>() {}
            );

            Spec s = new Spec();
            s.table = table;
            s.columns = toStringList(map.get("columns"));
            s.sheetName = Objects.toString(map.getOrDefault("sheetName", "Export"), "Export");

            // Anchors + template flags
            s.headerAnchor = objToStr(map.get("headerAnchor"));
            s.dataAnchor = objToStr(map.get("dataAnchor"));
            s.templateHasHeader = Boolean.parseBoolean(Objects.toString(map.getOrDefault("templateHasHeader", "false")));

            // Template FileRef fields
            s.templateStorage = objToStr(map.get("templateStorage"));
            s.templateFileId = objToStr(map.get("templateFileId"));
            s.templateFileName = objToStr(map.get("templateFileName"));

            Object vcols = map.get("virtualColumns");
            if (vcols instanceof Collection<?> col) {
                for (Object o : col) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;

                    VirtualColumn vc = new VirtualColumn();
                    vc.key = Objects.toString(m.get("key"), "");
                    vc.header = Objects.toString(m.get("header"), vc.key);
                    vc.delimiter = Objects.toString(m.getOrDefault("delimiter", " "), " ");
                    vc.concatOf = toStringList(m.get("concatOf"));

                    if (!vc.key.isBlank() && !vc.concatOf.isEmpty()) {
                        s.virtualColumns.add(vc);
                    }
                }
            }
            return Optional.of(s);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /* ===================== Helpers ===================== */

    private Map<String, String> buildDbToPropertyPathMap(DataGrid<?> grid) {
        Map<String, String> map = new LinkedHashMap<>();
        var edg = (EnhancedDataGrid) grid;
        for (var col : ((DataGrid<Object>) grid).getAllColumns()) {
            var mpp = edg.getColumnMetaPropertyPath(col);
            if (mpp == null) continue;
            MetaProperty mp = mpp.getMetaProperty();
            String db = safeLower(getDatabaseColumnName(mp));
            if (db != null && !db.isBlank()) {
                map.put(db, mpp.toPathString());
            }
        }
        return map;
    }

    private Object readByPath(Object bean, String path) {
        if (bean == null || path == null || path.isBlank()) return null;
        Object cur = bean;
        for (String seg : path.split("\\.")) {
            if (cur == null) return null;
            cur = invokeGetter(cur, seg);
        }
        return cur;
    }

    private Object invokeGetter(Object obj, String prop) {
        String base = prop.substring(0,1).toUpperCase(Locale.ROOT) + prop.substring(1);
        for (String name : new String[]{"get"+base, "is"+base}) {
            try { return obj.getClass().getMethod(name).invoke(obj); }
            catch (Exception ignore) {}
        }
        return null;
    }

    private List<String> toStringList(Object o) {
        if (!(o instanceof Collection<?> c)) return List.of();
        return c.stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.toList());
    }
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
        // thêm biến thể bỏ schema
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
        public List<String> columns = List.of();
        public String sheetName = "Export";

        // Template + anchors
        public String headerAnchor;
        public String dataAnchor;
        public boolean templateHasHeader;

        // FileRef của template trong FileStorage
        public String templateStorage;
        public String templateFileId;
        public String templateFileName;

        public List<VirtualColumn> virtualColumns = new ArrayList<>();
        public Spec withResolvedTable(String t){ this.table=t; return this; }

        public boolean hasTemplateRef() {
            return templateStorage != null && !templateStorage.isBlank()
                    && templateFileId != null && !templateFileId.isBlank()
                    && templateFileName != null && !templateFileName.isBlank();
        }
    }

    public static class VirtualColumn {
        public String key;
        public String header;
        public List<String> concatOf = List.of();
        public String delimiter = " ";
    }

    /* ===================== JSON WRITE ===================== */

    /** Ghi Spec về JSON để lưu DB, GIỮ các trường template/anchor. */
    private String specToJson(Spec s) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", s.columns == null ? List.of() : s.columns);
            out.put("sheetName", s.sheetName == null ? "Export" : s.sheetName);

            // Anchors + template
            out.put("headerAnchor", s.headerAnchor);
            out.put("dataAnchor", s.dataAnchor);
            out.put("templateHasHeader", s.templateHasHeader);
            out.put("templateStorage", s.templateStorage);
            out.put("templateFileId", s.templateFileId);
            out.put("templateFileName", s.templateFileName);

            List<Map<String, Object>> vlist = new ArrayList<>();
            if (s.virtualColumns != null) {
                for (VirtualColumn vc : s.virtualColumns) {
                    if (vc == null) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", vc.key);
                    m.put("header", vc.header);
                    m.put("concatOf", vc.concatOf == null ? List.of() : vc.concatOf);
                    m.put("delimiter", vc.delimiter == null ? " " : vc.delimiter);
                    vlist.add(m);
                }
            }
            out.put("virtualColumns", vlist);

            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"columns\":[],\"virtualColumns\":[]}";
        }
    }

    /* ===================== misc helpers ===================== */

    private static String objToStr(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
