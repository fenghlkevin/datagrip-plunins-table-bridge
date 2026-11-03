package com.tablebridge;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressIndicator;
import java.sql.*;
import java.util.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** Read-only, bounded comparison. Samples stay in memory and never enter recovery manifests. */
final class SyncPreview {
    static final int MAX_ROWS=50_000, MAX_BYTES=32*1024*1024, MAX_SAMPLES=20;
    private static final BigInteger MODULUS=BigInteger.ONE.shiftLeft(256);
    record Sample(String action,String key,List<String> changes) {}
    record Table(int index,String name,long inserts,long updates,long deletes,long retained,
                 RowDigest.Value source,RowDigest.Value target,List<Integer> types,List<Sample> samples) {
        Table { types=List.copyOf(types);samples=List.copyOf(samples); }
    }
    record Rows(Map<String,List<byte[]>> values,RowDigest.Value digest) {}
    static boolean sync(TransferPlan.Item item){return item.data()&&(item.action()==ExistingPolicy.UPSERT||item.action()==ExistingPolicy.MIRROR);}
    static List<Table> read(Project project,TransferPlan plan,ProgressIndicator indicator)throws Exception{
        TransferControl control=new TransferControl();control.attach(indicator);
        try(var a=TransferRunner.connect(project,plan.sourceNamespace);var b=TransferRunner.connect(project,plan.targetNamespace)){
            if(a.get()==b.get()||a.get().getRemoteConnection().equals(b.get().getRemoteConnection()))throw new SQLException("差异预览需要独立的来源与目标会话");
            Connection source=control.wrap(RemoteJdbc.connection(a.get().getRemoteConnection())),target=control.wrap(RemoteJdbc.connection(b.get().getRemoteConnection()));
            try{
                TransferRunner.configure(source,plan.sourceNamespace,plan.engine);TransferRunner.configure(target,plan.targetNamespace,plan.targetEngine);
                TargetValidation.distinct(source,target,plan);
                source.setTransactionIsolation(plan.engine==Engine.ORACLE?Connection.TRANSACTION_SERIALIZABLE:Connection.TRANSACTION_REPEATABLE_READ);
                target.setTransactionIsolation(plan.targetEngine==Engine.ORACLE?Connection.TRANSACTION_SERIALIZABLE:Connection.TRANSACTION_REPEATABLE_READ);
                source.setAutoCommit(false);target.setAutoCommit(false);
                List<Table> result=new ArrayList<>();
                for(int i=0;i<plan.items.size();i++)if(sync(plan.items.get(i))){
                    indicator.checkCanceled();var item=plan.items.get(i);indicator.setText(item.targetName());
                    var fields=item.columns().stream().map(TransferPlan.Column::name).toList();var keys=JobManifest.keys(plan,i);
                    DurableRunner.requirePrimaryKey(target,plan.targetEngine,plan.targetNamespace.getName(),item.targetName(),keys);
                    var types=TargetValidation.created(target,plan.targetEngine,plan.targetTable(item),item.columns()).types();
                    String from=plan.engine.qualified(plan.sourceNamespace.getName(),item.source().getName());
                    Rows left=scan(source,plan.engine,from,fields,keys,types,indicator::checkCanceled);
                    Rows right=scan(target,plan.targetEngine,plan.targetTable(item),fields,keys,types,indicator::checkCanceled);
                    result.add(compare(i,item.targetName(),fields,keys,types,item.action(),left,right));
                }
                return List.copyOf(result);
            }finally{try{target.rollback();}finally{source.rollback();}}
        }finally{control.detach();}
    }
    static Rows scan(Connection connection,Engine engine,String table,List<String> fields,List<String> keys,List<Integer> types,Runnable cancel)throws SQLException{
        if(keys.isEmpty()||!fields.containsAll(keys)||types.size()!=fields.size())throw new SQLException("差异预览要求完整主键与字段映射");
        String selected=engine==Engine.ORACLE?SpecialValues.oracleFields(connection,table,fields):String.join(",",fields.stream().map(engine::quote).toList());
        Map<String,List<byte[]>> values=new LinkedHashMap<>();long bytes=0,count=0;BigInteger sum=BigInteger.ZERO;
        try(Statement query=connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
            query.setFetchSize(engine==Engine.MYSQL?Integer.MIN_VALUE:250);query.setQueryTimeout(60);
            try(ResultSet rows=query.executeQuery("SELECT "+selected+" FROM "+table+(engine==Engine.ORACLE?" FETCH FIRST ":" LIMIT ")+(MAX_ROWS+1)+(engine==Engine.ORACLE?" ROWS ONLY":""))){
                var meta=rows.getMetaData();
                while(rows.next()){
                    cancel.run();if(++count>MAX_ROWS)throw new SQLException("差异预览每表每侧最多 50000 行；可跳过预览继续原有同步");
                    List<byte[]> row=new ArrayList<>();
                    for(int n=1;n<=fields.size();n++){
                        int raw=meta.getColumnType(n),type=types.get(n-1);if(type==Types.NULL)type=raw;
                        if(engine==Engine.ORACLE&&type==Types.DATE)type=Types.TIMESTAMP;
                        byte[] value=RowDigest.value(rows,n,raw,type,meta.getColumnTypeName(n));row.add(value);
                        bytes+=value==null?16:16L+value.length;if(bytes>MAX_BYTES)throw new SQLException("差异预览每表每侧内容最多 32 MiB；可跳过预览继续原有同步");
                    }
                    List<byte[]> key=keys.stream().map(k->row.get(fields.indexOf(k))).toList();
                    if(key.stream().anyMatch(Objects::isNull))throw new SQLException("差异预览发现空主键");
                    String identity=HexFormat.of().formatHex(hash(key));
                    if(values.put(identity,row)!=null)throw new SQLException("字段转换后的主键重复，无法安全预览");
                    sum=sum.add(new BigInteger(1,hash(row))).mod(MODULUS);
                }
            }
        }
        return new Rows(values,new RowDigest.Value(count,sum));
    }
    static byte[] hash(List<byte[]> row){
        try{MessageDigest hash=MessageDigest.getInstance("SHA-256");for(byte[] value:row){hash.update((byte)(value==null?0:1));if(value!=null){hash.update(ByteBuffer.allocate(4).putInt(value.length).array());hash.update(value);}}return hash.digest();}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static Table compare(int index,String name,List<String> fields,List<String> keys,List<Integer> types,ExistingPolicy policy,Rows source,Rows target){
        long inserts=0,updates=0,extra=0;List<Sample> samples=new ArrayList<>();
        for(var entry:source.values.entrySet()){
            var row=entry.getValue();var old=target.values.get(entry.getKey());
            if(old==null){inserts++;sample(samples,new Sample(UiText.text("新增"),key(fields,keys,types,row),List.of()));}
            else{List<String> changes=new ArrayList<>();for(int i=0;i<fields.size();i++)if(!keys.contains(fields.get(i))&&!Arrays.equals(old.get(i),row.get(i)))changes.add(fields.get(i)+": "+display(old.get(i),types.get(i))+" → "+display(row.get(i),types.get(i)));
                if(!changes.isEmpty()){updates++;sample(samples,new Sample(UiText.text("更新"),key(fields,keys,types,row),List.copyOf(changes)));}}
        }
        for(var entry:target.values.entrySet())if(!source.values.containsKey(entry.getKey())){extra++;sample(samples,new Sample(UiText.text(policy==ExistingPolicy.MIRROR?"删除":"保留"),key(fields,keys,types,entry.getValue()),List.of()));}
        return new Table(index,name,inserts,updates,policy==ExistingPolicy.MIRROR?extra:0,policy==ExistingPolicy.MIRROR?0:extra,source.digest,target.digest,types,samples);
    }
    private static void sample(List<Sample> samples,Sample sample){if(samples.size()<MAX_SAMPLES)samples.add(sample);}
    private static String key(List<String> fields,List<String> keys,List<Integer> types,List<byte[]> row){return String.join(", ",keys.stream().map(k->k+"="+display(row.get(fields.indexOf(k)),types.get(fields.indexOf(k)))).toList());}
    private static String display(byte[] value,int type){
        if(value==null)return "NULL";
        String text=Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB).contains(type)?"0x"+HexFormat.of().formatHex(Arrays.copyOf(value,Math.min(value.length,80))):new String(value,0,Math.min(value.length,640),StandardCharsets.UTF_8);
        text=text.replace("\\","\\\\").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t");return text.length()>160?text.substring(0,160)+"…":"\""+text+"\"";
    }
    static void verifySource(Connection source,TransferPlan plan,Runnable cancel)throws SQLException{
        for(Table table:plan.syncPreview){var item=plan.items.get(table.index);require(table.source,RowDigest.read(source,plan.engine,plan.engine.qualified(plan.sourceNamespace.getName(),item.source().getName()),item.columns().stream().map(TransferPlan.Column::name).toList(),table.types,cancel),table.name);}
    }
    static void verifyTarget(Connection target,TransferPlan plan,ResumableTable job,Runnable cancel)throws SQLException{
        for(Table table:plan.syncPreview){var item=plan.items.get(table.index);var fields=item.columns().stream().map(TransferPlan.Column::name).toList();
            if(job!=null&&job.get(table.index).phase().equals("APPLIED"))continue;
            require(table.target,RowDigest.read(target,plan.targetEngine,plan.targetTable(item),fields,table.types,cancel),table.name);
            if(job!=null)require(table.source,RowDigest.read(target,plan.targetEngine,job.stage(table.index),fields,table.types,cancel),table.name);
        }
    }
    static void require(RowDigest.Value expected,RowDigest.Value actual,String name)throws SQLException{if(!expected.equals(actual))throw new SQLException("差异预览已过期，请重新生成计划并预览："+name);}
    static String text(List<Table> tables){
        StringBuilder out=new StringBuilder(UiText.text("差异预览按内容校验规则估算；目标排序规则、类型转换和触发器可能影响实际结果。执行前复查数据，复查后并发写入仍可能影响结果。\n每表最多展示 20 条样例；样例包含数据库值，请注意分享范围。\n\n"));
        for(Table table:tables){out.append(table.name).append(" · ").append(UiText.text("新增")).append(' ').append(table.inserts).append(" · ").append(UiText.text("更新")).append(' ').append(table.updates).append(" · ").append(UiText.text("删除")).append(' ').append(table.deletes).append(" · ").append(UiText.text("保留")).append(' ').append(table.retained).append('\n');for(Sample sample:table.samples){out.append("  ").append(sample.action).append(" | ").append(sample.key).append('\n');for(String change:sample.changes)out.append("    ").append(change).append('\n');}out.append('\n');}return out.toString();
    }
}
