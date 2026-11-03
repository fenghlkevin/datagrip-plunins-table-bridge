package com.tablebridge;

import com.intellij.database.Dbms;
import com.intellij.database.model.*;
import com.intellij.database.model.basic.BasicElement;
import com.intellij.database.psi.*;
import com.intellij.database.util.*;
import com.intellij.database.view.DatabaseView;
import com.intellij.openapi.actionSystem.*;
import java.util.*;

final class DbSelection {
    static final Set<ObjectKind> KINDS = Set.of(ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.ROUTINE,
        ObjectKind.SEQUENCE, ObjectKind.OBJECT_TYPE, ObjectKind.COLLECTION_TYPE, ObjectKind.TABLE_TYPE, ObjectKind.ALIAS_TYPE);
    static boolean inTree(DataContext c) { return Boolean.TRUE.equals(DatabaseView.DATABASE_TREE_IS_ORIGIN.getData(c)); }
    static List<DbElement> elements(DataContext c) {
        DbElement[] elements = DatabaseView.DB_ELEMENTS.getData(c);
        if (elements != null) return Arrays.asList(elements);
        BasicElement[] basic = DatabaseView.DATABASE_ELEMENTS.getData(c);
        var project = CommonDataKeys.PROJECT.getData(c);
        if (basic == null || project == null) return List.of();
        return Arrays.stream(basic).map(e -> DbPsiFacade.getInstance(project).findElement(e)).filter(Objects::nonNull).toList();
    }
    static Engine engine(DbElement e) {
        Dbms dbms = e.getDataSource().getDbms();
        if (dbms == Dbms.MYSQL) return Engine.MYSQL;
        if (dbms == Dbms.POSTGRES) return Engine.POSTGRES;
        if (dbms == Dbms.ORACLE) return Engine.ORACLE;
        if (dbms == Dbms.MSSQL) return Engine.MSSQL;
        throw new IllegalArgumentException("当前仅支持 MySQL、PostgreSQL、Oracle、SQL Server 原生连接");
    }
    static DbElement namespace(DbElement e) {
        for (DbElement p = e; p != null && !(p instanceof DbDataSource); p = p.getParent()) {
            if (p.getKind() == ObjectKind.SCHEMA || (engine(e) == Engine.MYSQL && p.getKind() == ObjectKind.DATABASE)) return p;
        }
        return null;
    }
    static boolean canCopy(DataContext c) {
        if (!inTree(c)) return false;
        var list=elements(c);
        return !list.isEmpty() && list.stream().allMatch(e -> KINDS.contains(e.getKind()) && e.isValid());
    }
    static DbElement pasteTarget(DataContext c) {
        if (!inTree(c)) return null;
        var es=elements(c);
        if(es.size()!=1) return null;
        DbElement e=es.getFirst();
        if (e.getKind()!=ObjectKind.SCHEMA && e.getKind()!=ObjectKind.DATABASE) return null;
        try { return namespace(e); } catch (IllegalArgumentException ex) { return null; }
    }
    static String path(DbElement e) {
        String catalog=DasUtil.getCatalog(e),schema=DasUtil.getSchema(e);
        List<String> parts=new ArrayList<>();parts.add(e.getDataSource().getName());
        if(catalog!=null&&!catalog.isBlank())parts.add(catalog);
        String leaf=schema==null||schema.isBlank()?e.getName():schema;
        if(!leaf.equals(catalog))parts.add(leaf);
        return String.join(" / ",parts);
    }
    static BasicElement basic(DbElement e) {
        BasicElement b=DbImplUtilCore.getMaybeBasicElement(e);
        if(b==null) throw new IllegalArgumentException("对象元数据未加载："+e.getName());
        return b;
    }
    static String group(DbElement e) {
        if(e.getKind()==ObjectKind.TABLE) return "表";
        if(e.getKind()==ObjectKind.VIEW) return "视图";
        if(e.getKind()==ObjectKind.SEQUENCE) return "序列";
        if(e.getKind()==ObjectKind.ROUTINE) return e.getDelegate() instanceof DasRoutine routine && routine.getRoutineKind().name().contains("PROCEDURE") ? "存储过程" : "函数";
        return "类型";
    }
    static ObjectPath objectPath(DbElement e) {
        List<DbElement> parents=new ArrayList<>();
        for(DbElement p=e;p!=null && !(p instanceof DbDataSource);p=p.getParent()) parents.add(p);
        Collections.reverse(parents);ObjectPath result=ObjectPath.ROOT;
        for(var p:parents) result=result.append(p.getName(),p.getKind());
        return result;
    }
}
