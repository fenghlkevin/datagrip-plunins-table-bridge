package com.tablebridge;

import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** A durable, target-transaction checkpoint plus a task-owned staging snapshot. */
final class ResumableTable {
    static final int PAGE_SIZE=500;
    record Spec(String source,String target,List<String> fields,List<String> keys,List<Integer> types,boolean identity,ExistingPolicy policy){Spec{fields=List.copyOf(fields);keys=List.copyOf(keys);types=List.copyOf(types);if(keys.isEmpty()||!fields.containsAll(keys)||fields.size()!=types.size())throw new IllegalArgumentException("断点/同步要求已复制的完整主键");}}
    record State(String phase,long rows,String cursor,String digest){}
    record Result(long rows,long changed,boolean resumed){}
    private final Connection target;private final Engine engine;private final String schema,job,ledger;private final Runnable cancel;private final Consumer<String> log;
    ResumableTable(Connection target,Engine engine,String schema,String job,boolean resume,String fingerprint,Runnable cancel,Consumer<String> log)throws SQLException{
        UUID.fromString(job);if(engine==Engine.MSSQL)throw new SQLException("SQL Server 不支持此断点入口");this.target=target;this.engine=engine;this.schema=schema;this.job=job;this.cancel=cancel;this.log=log;ledger=engine.qualified(schema,"tb_j_"+job.replace("-","").substring(0,20));
        if(!resume){
            String number=engine==Engine.ORACLE?"number(19)":"bigint",text=engine==Engine.ORACLE?"clob":"text",varchar=engine==Engine.ORACLE?"varchar2":"varchar";
            RowCopier.execute(target,"CREATE TABLE "+ledger+" (item_id "+number+" PRIMARY KEY,phase "+varchar+"(32) NOT NULL,processed "+number+" NOT NULL,cursor_value "+text+",digest_value "+varchar+"(160))"+(engine==Engine.MYSQL?" ENGINE=InnoDB":""));
            put(-1,"JOB",0,"TableBridge:1:"+job,fingerprint,true);target.commit();
        }
        State root=get(-1);if(root==null||!root.cursor.equals("TableBridge:1:"+job)||!root.digest.equals(fingerprint))throw new SQLException("恢复任务与连接/模式/执行计划不匹配；未修改目标数据");
    }
    int structureProgress(int item,int total)throws SQLException{State s=get(-2-item);if(s==null)return 0;if(s.phase.equals("DDL_RUNNING"))throw new SQLException("结构执行的提交结果未确认，请检查目标并重新生成计划；不会自动重复 DDL");if(!s.phase.equals("DDL_DONE")||s.rows>total)throw new SQLException("结构检查点无效");return Math.toIntExact(s.rows);}
    void structureStarting(int item,int step,String hash)throws SQLException{boolean insert=get(-2-item)==null;put(-2-item,"DDL_RUNNING",step,"",hash,insert);target.commit();}
    void structureDone(int item,int step,String hash)throws SQLException{put(-2-item,"DDL_DONE",step,"",hash,false);target.commit();}
    boolean postCompleted(int id)throws SQLException{State s=get(1_000_000+id);if(s==null)return false;if(s.phase.equals("POST_RUNNING"))throw new SQLException("附属 DDL 的提交结果未确认，需检查目标后恢复；不会盲目重放");return s.phase.equals("POST_DONE");}
    void postStarting(int id,String sql)throws SQLException{put(1_000_000+id,"POST_RUNNING",0,"",fingerprint(sql),get(1_000_000+id)==null);target.commit();}
    void postDone(int id,String sql)throws SQLException{put(1_000_000+id,"POST_DONE",0,"",fingerprint(sql),false);target.commit();}
    String stage(int item){return engine.qualified(schema,"tb_s_"+job.replace("-","").substring(0,16)+"_"+Integer.toUnsignedString(item,36));}
    State get(int item)throws SQLException{try(PreparedStatement s=target.prepareStatement("SELECT phase,processed,cursor_value,digest_value FROM "+ledger+" WHERE item_id=?")){s.setInt(1,item);try(ResultSet r=s.executeQuery()){return r.next()?new State(r.getString(1),r.getLong(2),r.getString(3)==null?"":r.getString(3),r.getString(4)==null?"":r.getString(4)):null;}}}
    private void put(int item,String phase,long rows,String cursor,String digest,boolean insert)throws SQLException{
        try(PreparedStatement s=target.prepareStatement(insert?"INSERT INTO "+ledger+"(phase,processed,cursor_value,digest_value,item_id) VALUES(?,?,?,?,?)":"UPDATE "+ledger+" SET phase=?,processed=?,cursor_value=?,digest_value=? WHERE item_id=?")){s.setString(1,phase);s.setLong(2,rows);s.setString(3,cursor);s.setString(4,digest);s.setInt(5,item);int affected=s.executeUpdate();if(affected!=1)throw new SQLException("任务检查点并发修改/缺失");}
    }
    static String fingerprint(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String digest(RowDigest.Value v){return v.rows()+":"+v.sum().toString(16);}
    private void checkTransaction()throws SQLException{if(target.getAutoCommit())throw new SQLException("断点要求目标事务关闭自动提交");}
    long load(int item,Connection source,Engine from,Spec spec,LongConsumer progress)throws SQLException{
        checkTransaction();cancel.run();State state=get(item);if(state!=null&&Set.of("READY","APPLIED").contains(state.phase)){if(state.phase.equals("READY")&&!digest(RowDigest.read(target,engine,stage(item),spec.fields,spec.types,cancel)).equals(state.digest))throw new SQLException("恢复暂存快照已改变；不会写入目标表");return state.rows;}
        RowDigest.Value original=RowDigest.read(source,from,spec.source,spec.fields,spec.types,cancel);String snapshot=digest(original);
        if(state!=null&&!state.digest.equals(snapshot))throw new SQLException("来源已变化，不能混合旧检查点与新记录；请丢弃本任务暂存并重新开始");
        if(state==null){
            put(item,"STAGING",0,"",snapshot,true);target.commit();
            RowCopier.execute(target,"CREATE TABLE "+stage(item)+" AS SELECT "+String.join(",",spec.fields.stream().map(engine::quote).toList())+" FROM "+spec.target+" WHERE 1=0");
            if(engine==Engine.MYSQL)RowCopier.execute(target,"ALTER TABLE "+stage(item)+" ENGINE=InnoDB");
            put(item,"LOADING",0,"",snapshot,false);target.commit();state=get(item);
        }else if(state.phase.equals("STAGING"))throw new SQLException("暂存表创建结果未确认；请检查并丢弃该任务的暂存后重新开始");
        if(!state.phase.equals("LOADING"))throw new SQLException("检查点阶段不可恢复："+state.phase);
        long total=state.rows;List<KeyCursor.Value> cursor=KeyCursor.decode(state.cursor);
        int[] rawTypes,precision;List<Integer> keyPositions=spec.keys.stream().map(k->spec.fields.indexOf(k)+1).toList();
        try(Statement q=source.createStatement();ResultSet r=q.executeQuery("SELECT "+String.join(",",spec.fields.stream().map(from::quote).toList())+" FROM "+spec.source+" WHERE 1=0")){
            var meta=r.getMetaData();RowCopier.checkTypes(meta,spec.types,from,engine);rawTypes=new int[spec.fields.size()];precision=new int[rawTypes.length];for(int i=0;i<rawTypes.length;i++){String nativeType=meta.getColumnTypeName(i+1);rawTypes[i]=ZonedValues.timestamp(nativeType)?Types.TIMESTAMP_WITH_TIMEZONE:ZonedValues.time(nativeType)?Types.TIME_WITH_TIMEZONE:meta.getColumnType(i+1);if(from==Engine.ORACLE&&rawTypes[i]==Types.DATE)rawTypes[i]=Types.TIMESTAMP;precision[i]=meta.getPrecision(i+1);}
        }
        for(int position:keyPositions)if(Set.of(Types.ARRAY,Types.STRUCT,Types.SQLXML,Types.BLOB,Types.CLOB,Types.FLOAT,Types.DOUBLE,Types.REAL).contains(rawTypes[position-1]))throw new SQLException("主键类型无法安全用作断点排序；请选用整数、精确数值、文本、UUID、日期或二进制主键");
        String selected=from==Engine.ORACLE?SpecialValues.oracleFields(source,spec.source,spec.fields):String.join(",",spec.fields.stream().map(from::quote).toList());
        String base="SELECT "+selected+" FROM "+spec.source,order=" ORDER BY "+String.join(",",spec.keys.stream().map(from::quote).toList()),limit=from==Engine.ORACLE?" FETCH FIRST "+PAGE_SIZE+" ROWS ONLY":" LIMIT "+PAGE_SIZE;
        List<String> placeholders=new ArrayList<>();for(int type:spec.types)placeholders.add(engine==Engine.ORACLE&&type==Types.TIMESTAMP_WITH_TIMEZONE?"TO_TIMESTAMP_TZ(?, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9 TZH:TZM TZR')":"?");
        String insert="INSERT INTO "+stage(item)+" ("+String.join(",",spec.fields.stream().map(engine::quote).toList())+") VALUES ("+String.join(",",placeholders)+")";
        while(true){
            cancel.run();long page=0;List<KeyCursor.Value> last=cursor;
            try(PreparedStatement read=source.prepareStatement(base+(cursor.isEmpty()?"":" WHERE "+KeyCursor.after(from,spec.keys))+order+limit)){
                read.setQueryTimeout(60);read.setFetchSize(PAGE_SIZE);if(!cursor.isEmpty()){if(cursor.size()!=spec.keys.size())throw new SQLException("断点主键数量不匹配");KeyCursor.bind(read,cursor);}
                try(ResultSet originalRows=read.executeQuery();ResultSet rows=RemoteBulk.buffered(originalRows,rawTypes);PreparedStatement write=writer(insert,spec.types)){
                    write.setQueryTimeout(60);while(rows.next()){cancel.run();for(int i=0;i<spec.fields.size();i++)RowCopier.bindColumn(rows,write,rawTypes[i],precision[i],i+1,from,engine,spec.types.get(i),spec.fields.get(i));write.addBatch();last=KeyCursor.read(rows,keyPositions,rawTypes);page++;if(page%RowCopier.BATCH_SIZE==0)batch(write);}
                    if(page%RowCopier.BATCH_SIZE!=0)batch(write);
                }
            }catch(SQLException|RuntimeException failure){try{target.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
            total=Math.addExact(total,page);put(item,"LOADING",total,KeyCursor.encode(last),snapshot,false);cancel.run();target.commit();cursor=last;progress.accept(total);
            if(page<PAGE_SIZE)break;
        }
        if(!RowDigest.read(target,engine,stage(item),spec.fields,spec.types,cancel).equals(original))throw new SQLException("暂存快照内容校验不一致；未修改目标表");
        try(Statement s=target.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM (SELECT "+String.join(",",spec.keys.stream().map(engine::quote).toList())+" FROM "+stage(item)+" GROUP BY "+String.join(",",spec.keys.stream().map(engine::quote).toList())+" HAVING COUNT(*)>1) "+(engine==Engine.ORACLE?"":"AS ")+"duplicates")){if(!r.next()||r.getLong(1)>0)throw new SQLException("映射后的主键重复；未修改目标表");}
        put(item,"READY",total,KeyCursor.encode(cursor),snapshot,false);target.commit();log.accept("暂存快照校验完成："+spec.target+" · "+total+" 行");return total;
    }
    private PreparedStatement writer(String sql,List<Integer> types)throws SQLException{
        if(engine==Engine.MYSQL&&target instanceof RemoteBulk.ConnectionApi bulk&&types.stream().allMatch(RemoteBulk::supported)){List<com.intellij.database.remote.jdbc.ColumnInfo> infos=new ArrayList<>();for(int i=0;i<types.size();i++)infos.add(new com.intellij.database.remote.jdbc.ColumnInfo("","",types.get(i),i));return bulk.prepareBulk(sql,infos);}return target.prepareStatement(sql);
    }
    private static void batch(PreparedStatement statement)throws SQLException{try{for(int n:statement.executeBatch())if(n==Statement.EXECUTE_FAILED)throw new SQLException("暂存批次写入失败");statement.clearBatch();}catch(AssertionError error){if(statement.getConnection().isClosed())throw new SQLRecoverableException("驱动在已断开的连接上终止批次","08003",error);throw error;}}
    long apply(int item,Spec spec,boolean deleteMissing)throws SQLException{
        checkTransaction();State state=get(item);if(state==null)throw new SQLException("暂存检查点缺失");if(state.phase.equals("APPLIED"))return 0;if(!state.phase.equals("READY"))throw new SQLException("暂存快照尚未完成");cancel.run();long changed=0;RowDigest.Value appendExpected=spec.policy==ExistingPolicy.APPEND?RowDigest.read(target,engine,spec.target,spec.fields,spec.types,cancel).plus(RowDigest.read(target,engine,stage(item),spec.fields,spec.types,cancel)):null;
        if(spec.policy==ExistingPolicy.UPSERT||spec.policy==ExistingPolicy.MIRROR){for(String sql:SyncSql.merge(engine,spec.target,stage(item),spec.fields,spec.keys,spec.types,spec.identity))changed=Math.addExact(changed,update(sql));if(deleteMissing)changed=Math.addExact(changed,update(SyncSql.deleteMissing(engine,spec.target,stage(item),spec.keys)));}
        else{
            if(spec.policy==ExistingPolicy.CLEAR)update("DELETE FROM "+spec.target);
            String columns=String.join(",",spec.fields.stream().map(engine::quote).toList());changed=update("INSERT INTO "+spec.target+" ("+columns+")"+(engine==Engine.POSTGRES&&spec.identity?" OVERRIDING SYSTEM VALUE":"")+" SELECT "+columns+" FROM "+stage(item));
        }
        if(appendExpected!=null&&!appendExpected.equals(RowDigest.read(target,engine,spec.target,spec.fields,spec.types,cancel)))throw new SQLException("追加目标内容校验失败；本批写入回滚");
        // A lost commit acknowledgement is resolved by re-reading this marker, not replaying DML.
        put(item,"APPLIED",state.rows,state.cursor,state.digest,false);return changed;
    }
    long deleteMissing(int item,Spec spec)throws SQLException{return update(SyncSql.deleteMissing(engine,spec.target,stage(item),spec.keys));}
    void verify(int item,Spec spec)throws SQLException{
        if(spec.policy!=ExistingPolicy.UPSERT&&spec.policy!=ExistingPolicy.MIRROR&&spec.policy!=ExistingPolicy.CLEAR&&spec.policy!=ExistingPolicy.FAIL&&spec.policy!=ExistingPolicy.RECREATE)return;
        RowDigest.Value expected=RowDigest.read(target,engine,stage(item),spec.fields,spec.types,cancel);
        RowDigest.Value actual;
        if(spec.policy==ExistingPolicy.UPSERT){String selected=String.join(",",spec.fields.stream().map(f->"t."+engine.quote(f)).toList());actual=RowDigest.query(target,engine,"SELECT "+selected+" FROM "+spec.target+" t WHERE EXISTS(SELECT 1 FROM "+stage(item)+" s WHERE "+SyncSql.matches(engine,spec.keys,"t","s")+")",spec.fields,spec.types,cancel);}
        else actual=RowDigest.read(target,engine,spec.target,spec.fields,spec.types,cancel);
        if(!expected.equals(actual))throw new SQLException("目标内容校验失败，可能由目标触发器/转换导致；本批目标 DML 回滚");
    }
    private long update(String sql)throws SQLException{cancel.run();try(Statement s=target.createStatement()){s.setQueryTimeout(60);return s.executeUpdate(sql);}}
    void cleanup(List<Integer> items)throws SQLException{
        if(!get(-1).cursor.equals("TableBridge:1:"+job))throw new SQLException("暂存任务所有者不匹配");for(int i:items){State state=get(i);if(state!=null){try{RowCopier.execute(target,"DROP TABLE "+(engine==Engine.ORACLE?"":"IF EXISTS ")+stage(i));}catch(SQLException ex){if(engine!=Engine.ORACLE||ex.getErrorCode()!=942)throw ex;}}}RowCopier.execute(target,"DROP TABLE "+ledger);target.commit();
    }
}
