package com.tablebridge;

import java.sql.*;
import java.util.*;

/** Primary-key-directed DML. Secondary unique conflicts fail, never update another row. */
final class SyncSql {
    static String matches(Engine e,List<String> keys,String left,String right){return String.join(" AND ",keys.stream().map(k->left+"."+e.quote(k)+"="+right+"."+e.quote(k)).toList());}
    static List<String> merge(Engine e,String table,String stage,List<String> fields,List<String> keys,List<Integer> types,boolean identity){
        if(keys.isEmpty()||!fields.containsAll(keys)||types.size()!=fields.size())throw new IllegalArgumentException("增量/同步要求完整主键与字段类型");
        if(e==Engine.MSSQL)throw new IllegalArgumentException("本同步入口不支持 SQL Server");
        List<String> values=fields.stream().filter(f->!keys.contains(f)).toList();String on=matches(e,keys,"t","s"),columns=String.join(",",fields.stream().map(e::quote).toList()),select=String.join(",",fields.stream().map(f->"s."+e.quote(f)).toList());
        List<String> changed=new ArrayList<>();for(String f:values){String a="t."+e.quote(f),b="s."+e.quote(f);int type=types.get(fields.indexOf(f));changed.add(switch(e){
            case MYSQL->"NOT (BINARY "+a+" <=> BINARY "+b+")";
            case POSTGRES->"CAST("+a+" AS text) IS DISTINCT FROM CAST("+b+" AS text)";
            case ORACLE->Set.of(Types.BLOB,Types.CLOB,Types.NCLOB).contains(type)?"("+a+" IS NULL AND "+b+" IS NOT NULL OR "+a+" IS NOT NULL AND "+b+" IS NULL OR DBMS_LOB.COMPARE("+a+","+b+")<>0)":"DECODE("+a+","+b+",0,1)=1";
            default->throw new IllegalArgumentException("未支持的同步数据库");});}
        String change=changed.isEmpty()?"1=0":"("+String.join(" OR ",changed)+")";List<String> sql=new ArrayList<>();
        if(e==Engine.ORACLE){String update=values.isEmpty()?"":" WHEN MATCHED THEN UPDATE SET "+String.join(",",values.stream().map(f->"t."+e.quote(f)+"=s."+e.quote(f)).toList())+" WHERE "+change;
            sql.add("MERGE INTO "+table+" t USING "+stage+" s ON ("+on+")"+update+" WHEN NOT MATCHED THEN INSERT ("+columns+") VALUES ("+select+")");
        }else{
            if(!values.isEmpty()){String assignments=String.join(",",values.stream().map(f->(e==Engine.MYSQL?"t.":"")+e.quote(f)+"=s."+e.quote(f)).toList());sql.add(e==Engine.MYSQL?"UPDATE "+table+" t JOIN "+stage+" s ON "+on+" SET "+assignments+" WHERE "+change:"UPDATE "+table+" t SET "+assignments+" FROM "+stage+" s WHERE "+on+" AND "+change);}
            sql.add("INSERT INTO "+table+" ("+columns+")"+(e==Engine.POSTGRES&&identity?" OVERRIDING SYSTEM VALUE":"")+" SELECT "+select+" FROM "+stage+" s WHERE NOT EXISTS (SELECT 1 FROM "+table+" t WHERE "+on+")");
        }
        return List.copyOf(sql);
    }
    static String deleteMissing(Engine e,String table,String stage,List<String> keys){if(keys.isEmpty())throw new IllegalArgumentException("同步删除要求主键");return e==Engine.MYSQL?"DELETE t FROM "+table+" t WHERE NOT EXISTS (SELECT 1 FROM "+stage+" s WHERE "+matches(e,keys,"t","s")+")":"DELETE FROM "+table+" t WHERE NOT EXISTS (SELECT 1 FROM "+stage+" s WHERE "+matches(e,keys,"t","s")+")";}
}
