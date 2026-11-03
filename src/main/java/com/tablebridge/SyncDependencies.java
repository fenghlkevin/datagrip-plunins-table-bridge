package com.tablebridge;

import java.sql.*;
import java.util.*;

/** Orders selected destination tables from actual imported-key metadata. */
final class SyncDependencies {
    static List<Integer> order(Connection c,Engine engine,String schema,List<String> tables)throws SQLException{
        Map<String,Integer> selected=new HashMap<>();for(int i=0;i<tables.size();i++)if(tables.get(i)!=null)selected.put(tables.get(i),i);
        boolean allDeferrable=true;Map<Integer,Set<Integer>> graph=new LinkedHashMap<>();for(var entry:selected.entrySet()){
            Set<Integer> parents=new LinkedHashSet<>();try(ResultSet keys=c.getMetaData().getImportedKeys(engine==Engine.MYSQL?schema:c.getCatalog(),engine==Engine.MYSQL?null:schema,entry.getKey())){while(keys.next()){
                String parentSchema=keys.getString(engine==Engine.MYSQL?"PKTABLE_CAT":"PKTABLE_SCHEM");Integer parent=selected.get(keys.getString("PKTABLE_NAME"));if(Objects.equals(parentSchema,schema)&&parent!=null&&parent.intValue()!=entry.getValue()){parents.add(parent);allDeferrable&=keys.getInt("DEFERRABILITY")!=DatabaseMetaData.importedKeyNotDeferrable;}
            }}graph.put(entry.getValue(),parents);
        }
        List<Integer> result=new ArrayList<>();Set<Integer> active=new HashSet<>(),done=new HashSet<>();try{for(int index:graph.keySet())visit(index,graph,active,done,result);}catch(SQLException cycle){if(engine!=Engine.POSTGRES||!allDeferrable)throw cycle;RowCopier.execute(c,"SET CONSTRAINTS ALL DEFERRED");result=new ArrayList<>(graph.keySet());Collections.sort(result);}return result;
    }
    private static void visit(int index,Map<Integer,Set<Integer>> graph,Set<Integer> active,Set<Integer> done,List<Integer> result)throws SQLException{if(done.contains(index))return;if(!active.add(index))throw new SQLException("目标表存在循环外键，无法安全自动排列同步顺序；请提供独立恢复策略");for(int parent:graph.getOrDefault(index,Set.of()))visit(parent,graph,active,done,result);active.remove(index);done.add(index);result.add(index);}
}
