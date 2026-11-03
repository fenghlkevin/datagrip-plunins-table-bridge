package com.tablebridge;

import java.util.*;

/** Reviewed table DDL, with an explicit separator rather than splitting routine-body semicolons. */
final class TableStructureSql {
    static final String SEPARATOR="-- @tablebridge-next";
    record Definition(String create,List<String> after,String text){}
    static Definition validate(Engine engine,String schema,String table,String script){
        if(script==null||script.length()>262144||script.indexOf('\0')>=0)throw new IllegalArgumentException("完整结构不得超过 256 KiB");
        if(engine==Engine.MSSQL)throw new IllegalArgumentException("此完整结构入口支持 MySQL、PostgreSQL、Oracle");
        String[] parts=script.split("(?m)^\\s*-- @tablebridge-next\\s*$",-1);
        if(parts.length>1000)throw new IllegalArgumentException("附属结构超过 1000 条");
        String create=CustomObjectSql.validate(engine,schema,table,"TABLE",parts[0]);
        List<String> after=new ArrayList<>();Set<String> names=new HashSet<>();
        for(int p=1;p<parts.length;p++){
            String sql=parts[p].trim();List<SqlNames.Token> ts=meaningful(sql);
            if(ts.isEmpty())throw new IllegalArgumentException("结构分隔符之间不可为空");
            int i=0;
            if(word(ts,0,"create")){
                i=(word(ts,1,"unique")||word(ts,1,"fulltext")||word(ts,1,"spatial")||word(ts,1,"bitmap"))?2:1;
                if(word(ts,i,"index")){
                    int on=i+2;
                    if(on<ts.size()&&ts.get(on).raw().equals(".")){if(engine==Engine.POSTGRES||!schema.equals(ts.get(i+1).identifier()))throw new IllegalArgumentException("索引模式必须符合目标方言和目标模式");requireName(engine,ts,on+1);on+=2;}
                    requireName(engine,ts,i+1);if(!word(ts,on,"on"))throw new IllegalArgumentException("索引必须使用 CREATE [UNIQUE] INDEX name ON 目标表");
                    bound(engine,ts,on+1,schema,table);single(sql);String key="index:"+ts.get(i+1).identifier();if(!names.add(key))throw new IllegalArgumentException("重复索引名称");
                }else if(word(ts,i,"trigger")){
                    requireName(engine,ts,i+1);int end=i+2;String name=ts.get(i+1).identifier();
                    if(end<ts.size()&&ts.get(end).raw().equals(".")){if(engine==Engine.POSTGRES)throw new IllegalArgumentException("PostgreSQL 触发器名称不得限定模式");if(!name.equals(schema))throw new IllegalArgumentException("触发器模式必须是目标模式");requireName(engine,ts,end+1);name=ts.get(end+1).identifier();end+=2;}
                    else if(engine!=Engine.POSTGRES)throw new IllegalArgumentException("触发器名称必须限定目标模式");
                    int on=-1;for(int n=end;n<ts.size();n++)if(word(ts,n,"on")){on=n;break;}
                    if(on<0)throw new IllegalArgumentException("触发器缺少 ON 目标表");bound(engine,ts,on+1,schema,table);
                    // Reuse block-aware validation; PG's table-scoped name is expanded only for validation.
                    String normalized="CREATE TRIGGER "+engine.qualified(schema,name)+" "+join(ts.subList(end,ts.size()));
                    CustomObjectSql.validate(engine,schema,name,"TRIGGER",normalized);
                    if(!names.add("trigger:"+name))throw new IllegalArgumentException("重复触发器名称");
                }else throw new IllegalArgumentException("附属 CREATE 仅支持 INDEX/TRIGGER；依赖函数须单独选择");
            }else if(word(ts,0,"alter")&&word(ts,1,"table")){
                bound(engine,ts,2,schema,table);if(!word(ts,5,"add")||!word(ts,6,"constraint"))throw new IllegalArgumentException("只允许目标表 ADD CONSTRAINT");single(sql);
            }else if(word(ts,0,"comment")&&word(ts,1,"on")&&(word(ts,2,"table")||word(ts,2,"column"))){bound(engine,ts,3,schema,table);single(sql);}
            else throw new IllegalArgumentException("附属结构仅支持索引、触发器、约束与注释");
            after.add(sql);
        }
        return new Definition(create,List.copyOf(after),script.trim());
    }
    static void requireAutomaticTriggerReferences(String sql){
        if(!CrossStructure.isTrigger(sql))return;
        var ts=meaningful(sql);
        for(int i=0;i+1<ts.size();i++){
            if(word(ts,i,"execute")&&word(ts,i+1,"immediate"))throw new IllegalArgumentException("动态 SQL 触发器需在完整结构中审核");
            String word=ts.get(i).raw().toLowerCase(Locale.ROOT);
            int next=i+1;
            if(word.equals("execute")&&word(ts,next,"function"))next++;
            else if(!Set.of("from","join","into","update","call").contains(word))continue;
            if(next>=ts.size()||ts.get(next).identifier()==null||Set.of("on","of","or").contains(ts.get(next).raw().toLowerCase(Locale.ROOT)))continue;
            if(next+1>=ts.size()||!ts.get(next+1).raw().equals("."))throw new IllegalArgumentException("触发器有未限定的对象引用，请在高级 → 完整结构中确认目标依赖："+ts.get(next).raw());
        }
    }
    static void requirePortableSource(String sql){
        var ts=meaningful(sql);
        for(var token:ts)if(!token.literal()&&Set.of("collate","collation","fulltext","spatial","include","tablespace","partition","compress","invisible","nulls","bitmap").contains(token.raw().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("来源含高级结构 "+token.raw()+"，请在高级 → 完整结构中转换并审核，避免丢失语义");
        for(int i=0;i+1<ts.size();i++)if(word(ts,i,"using")&&!word(ts,i+1,"btree"))throw new IllegalArgumentException("索引方法需在高级 → 完整结构中转换："+ts.get(i+1).raw());
    }
    private static String join(List<SqlNames.Token> ts){return String.join(" ",ts.stream().map(SqlNames.Token::raw).toList());}
    private static List<SqlNames.Token> meaningful(String sql){return SqlNames.tokens(sql).stream().filter(t->!t.raw().isBlank()&&!t.raw().startsWith("--")&&!t.raw().startsWith("/*")).toList();}
    private static boolean word(List<SqlNames.Token> ts,int i,String word){return i<ts.size()&&ts.get(i).raw().equalsIgnoreCase(word);}
    private static void bound(Engine engine,List<SqlNames.Token> ts,int i,String schema,String table){
        if(i+2>=ts.size()||!schema.equals(ts.get(i).identifier())||!ts.get(i+1).raw().equals(".")||!table.equals(ts.get(i+2).identifier()))throw new IllegalArgumentException("结构必须绑定目标表 "+engine.qualified(schema,table));
        requireName(engine,ts,i);requireName(engine,ts,i+2);
    }
    private static void requireName(Engine engine,List<SqlNames.Token> ts,int i){
        if(i>=ts.size()||ts.get(i).identifier()==null)throw new IllegalArgumentException("结构名称未加载");
        var t=ts.get(i);String name=t.identifier();
        if(!t.raw().equals(engine.quote(name))&&(!t.raw().equals(name)||engine==Engine.POSTGRES&&!name.equals(name.toLowerCase(Locale.ROOT))||engine==Engine.ORACLE&&!name.equals(name.toUpperCase(Locale.ROOT))))throw new IllegalArgumentException("结构名称须使用目标方言引号");
    }
    private static void single(String sql){var ts=meaningful(sql);for(int i=0;i<ts.size()-1;i++)if(ts.get(i).raw().equals(";"))throw new IllegalArgumentException("一段只允许一条附属结构语句");}
}
