package com.tablebridge;

import java.util.*;

/** Scalar-attribute composite/object type translation; not a domain or collection coercion. */
final class PortableType {
    static String create(Engine from,Engine target,String namespace,String name,List<CrossEngineDdl.Column> attributes){
        if(!Set.of(Engine.POSTGRES,Engine.ORACLE).contains(from)||!Set.of(Engine.POSTGRES,Engine.ORACLE).contains(target)||from==target)throw new IllegalArgumentException("MySQL 无独立复合类型；需选择类型展开/仿真策略");
        if(attributes.isEmpty())throw new IllegalArgumentException("复合类型属性未加载，或并非可转换的对象类型："+name);
        Set<String> names=new HashSet<>();List<String> definitions=new ArrayList<>();
        for(var field:attributes){
            if(!names.add(field.name()))throw new IllegalArgumentException("复合类型属性名称重复："+field.name());
            if(field.notNull()||field.identity()||field.computed()||field.defaultValue()!=null&&!field.defaultValue().isBlank())throw new IllegalArgumentException("复合属性带约束/计算表达式，需明确转换策略："+field.name());
            definitions.add(target.quote(field.name())+" "+CrossEngineDdl.type(field,from,target).sql());
        }
        return "CREATE TYPE "+target.qualified(namespace,name)+" AS "+(target==Engine.ORACLE?"OBJECT ":"")+"("+String.join(", ",definitions)+")";
    }
}
