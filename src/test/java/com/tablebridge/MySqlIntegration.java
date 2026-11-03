package com.tablebridge;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.math.BigDecimal;

/** Run only against the task-owned disposable server. Verifies datadir before any writes. */
public final class MySqlIntegration {
    private static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){try{run(args);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
    private static void run(String[] args)throws Exception{
        String url="jdbc:mysql://127.0.0.1:33316/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
        try(Connection source=DriverManager.getConnection(url,"root","");Connection target=DriverManager.getConnection(url,"root","")){
            try(Statement s=source.createStatement();ResultSet rs=s.executeQuery("SELECT @@datadir")){rs.next();check(Path.of(rs.getString(1)).toRealPath().equals(Path.of(args[0]).toRealPath()),"isolated datadir verified");}
            for(String sql:List.of("DROP DATABASE IF EXISTS tb_target","DROP DATABASE IF EXISTS tb_source","CREATE DATABASE tb_source","CREATE DATABASE tb_target","SET SESSION sql_mode='STRICT_ALL_TABLES,NO_AUTO_VALUE_ON_ZERO'","CREATE TABLE tb_source.items (id bigint PRIMARY KEY AUTO_INCREMENT, amount decimal(20,4), note varchar(100), payload blob)"))RowCopier.execute(source,sql);
            try(PreparedStatement write=source.prepareStatement("INSERT INTO tb_source.items VALUES(?,?,?,?)")){for(int i=0;i<603;i++){write.setLong(1,i);write.setBigDecimal(2,new BigDecimal("1234567890123456.1234"));if(i%2==0)write.setNull(3,Types.VARCHAR);else write.setString(3,"中文🙂'"+i);write.setBytes(4,new byte[]{0,(byte)255,(byte)i});write.addBatch();}write.executeBatch();}
            RowCopier.execute(target,"SET SESSION sql_mode='STRICT_ALL_TABLES,NO_AUTO_VALUE_ON_ZERO'");
            RowCopier.execute(target,"CREATE TABLE tb_target.items LIKE tb_source.items");source.setAutoCommit(false);target.setAutoCommit(false);
            var columns=List.of("id","amount","note","payload");
            long copied=RowCopier.copy(source,target,Engine.MYSQL,"`tb_source`.`items`","`tb_target`.`items`",columns,true,()->{},n->{});check(copied==603,"603 rows streamed through real JDBC");check(TargetValidation.count(target,"tb_target.items",Engine.MYSQL)==603,"target count");target.commit();
            try(Statement s=target.createStatement();ResultSet rs=s.executeQuery("SELECT id,amount,note,hex(payload) FROM tb_target.items WHERE id IN (0,1) ORDER BY id")){check(rs.next()&&rs.getLong(1)==0&&rs.getString(3)==null,"id zero and NULL preserved");check(rs.getBigDecimal(2).equals(new BigDecimal("1234567890123456.1234")),"decimal fidelity");check(rs.next()&&rs.getString(3).equals("中文🙂'1")&&rs.getString(4).equals("00FF01"),"unicode binary fidelity");}
            boolean duplicate=false;try{RowCopier.copy(source,target,Engine.MYSQL,"tb_source.items","tb_target.items",columns,true,()->{},n->{});}catch(SQLException expected){duplicate=true;target.rollback();}check(duplicate&&TargetValidation.count(target,"tb_target.items",Engine.MYSQL)==603,"append collision fails without overwriting old rows");
            TargetValidation.clearAllowed(target,Engine.MYSQL,null,"tb_target","items");RowCopier.execute(target,"DELETE FROM tb_target.items");target.rollback();check(TargetValidation.count(target,"tb_target.items",Engine.MYSQL)==603,"clear rollback restores rows");
            RowCopier.execute(target,"DELETE FROM tb_target.items");long fast=ServerCopier.copy(target,Engine.MYSQL,"tb_source.items","tb_target.items",columns,true,()->false);check(fast==603,"server insert select");target.commit();
            RowCopier.execute(target,"CREATE TABLE tb_source.more LIKE tb_source.items");RowCopier.execute(target,"INSERT INTO tb_source.more VALUES(1000,1,'new',NULL)");target.commit();long appended=ServerCopier.copy(target,Engine.MYSQL,"tb_source.more","tb_target.items",columns,true,()->false,603);check(appended==1&&TargetValidation.count(target,"tb_target.items",Engine.MYSQL)==604,"server append baseline");target.commit();
            RowCopier.execute(target,"CREATE TABLE tb_target.child(id bigint PRIMARY KEY, parent_id bigint, FOREIGN KEY(parent_id) REFERENCES tb_target.items(id) ON DELETE CASCADE)");boolean blocked=false;try{TargetValidation.clearAllowed(target,Engine.MYSQL,null,"tb_target","items");}catch(SQLException expected){blocked=true;}check(blocked,"cascade clear blocked before delete");
            var mapped=CrossEngineDdl.build(Engine.POSTGRES,Engine.MYSQL,"tb_target","from_pg",List.of(new CrossEngineDdl.Column("id","bigint",Types.BIGINT,19,0,true,true,null,false),new CrossEngineDdl.Column("uuid","uuid",Types.OTHER,0,0,false,false,null,false)),List.of("id"));RowCopier.execute(target,mapped.sql());RowCopier.execute(target,"INSERT INTO tb_target.from_pg(uuid) VALUES('27b4d3f4-bae6-4b89-9b3c-17d57b34fe95')");check(TargetValidation.count(target,"tb_target.from_pg",Engine.MYSQL)==1,"PG-to-MySQL generated DDL executes with identity");
            testBulkTransport(source,target);
            testTemporalDefaults(target);
            testCancellation(url);
            target.rollback();source.rollback();
        }
        String result="PASS "+checks+" real MySQL integration checks; disposable local MySQL 8.0.43. PostgreSQL endpoint not exercised.\n";Files.writeString(Path.of(args[1]),result);System.out.print(result);System.exit(0);
    }
    static void testBulkTransport(Connection source,Connection target)throws Exception{
        RowCopier.execute(target,"CREATE TABLE tb_target.bulk_items LIKE tb_source.items");
        ClassLoader loader=MySqlIntegration.class.getClassLoader();
        var sourceRemote=new com.intellij.database.remote.jdbc.impl.RemoteConnectionImpl(source,new com.intellij.database.remote.jdbc.helpers.MysqlJdbcHelper("mysql","8.0.43",source,loader));
        var targetRemote=new com.intellij.database.remote.jdbc.impl.RemoteConnectionImpl(target,new com.intellij.database.remote.jdbc.helpers.MysqlJdbcHelper("mysql","8.0.43",target,loader));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        RowCopier.execute(target,"CREATE TABLE tb_target.legacy_batch LIKE tb_source.items");
        String legacySql="INSERT INTO tb_target.legacy_batch(id,amount,note,payload) VALUES(?,?,?,?)";
        var infos=List.of(new com.intellij.database.remote.jdbc.ColumnInfo("","",Types.BIGINT,0),new com.intellij.database.remote.jdbc.ColumnInfo("","",Types.DECIMAL,1),new com.intellij.database.remote.jdbc.ColumnInfo("","",Types.VARCHAR,2),new com.intellij.database.remote.jdbc.ColumnInfo("","",Types.VARBINARY,3));
        var legacy=targetRemote.prepareBatchStatement(new com.intellij.database.remote.jdbc.impl.RemoteBatchPreparedStatementImpl.Configuration(legacySql,legacySql,null,infos,false,false,true,1));
        try{
            List<Object[]> values=new ArrayList<>();for(int i=0;i<429;i++)values.add(new Object[]{(long)i,new BigDecimal("1.1234"),"legacy",new byte[]{0,1}});
            long before=sessionInserts(target);var errors=legacy.addBatch(new com.intellij.database.remote.dbimport.BatchRecords(values,0,0),legacySql);
            long insertCalls=sessionInserts(target)-before;System.out.println("Previous native batch: 429 rows / "+insertCalls+" MySQL INSERTs");
            check(errors.isEmpty()&&insertCalls==429,"previous single-line driver batch reproduced per-row database requests");
        }finally{legacy.close();target.rollback();}
        TransferControl control=new TransferControl();
        try{
            Connection src=control.wrap(RemoteJdbc.connection((com.intellij.database.remote.jdbc.RemoteConnection)countRemote(sourceRemote,calls))),dst=control.wrap(RemoteJdbc.connection((com.intellij.database.remote.jdbc.RemoteConnection)countRemote(targetRemote,calls)));
            long insertsBefore=sessionInserts(target);
            long start=System.nanoTime();
            long n=RowCopier.copy(src,dst,Engine.MYSQL,"tb_source.items","tb_target.bulk_items",List.of("id","amount","note","payload"),true,()->{},v->{});
            System.out.printf("Native driver bulk: %d rows %.3f s%n",n,(System.nanoTime()-start)/1e9);
            check(n==603,"native driver bulk count");
            long inserts=sessionInserts(target)-insertsBefore;
            System.out.println("MySQL INSERT executions for 603 rows: "+inserts);check(inserts==3,"603 rows use three server INSERT statements including tail batch");
            System.out.println("Native boundary calls for 603 rows: "+calls.get());check(calls.get()<150,"native bulk avoids per-cell driver calls");
            try(Statement q=target.createStatement();ResultSet rs=q.executeQuery("SELECT a.id FROM tb_source.items a JOIN tb_target.bulk_items b ON a.id=b.id WHERE NOT(a.amount <=> b.amount) OR NOT(a.note <=> b.note) OR NOT(a.payload <=> b.payload)")){check(!rs.next(),"native bulk decimal/null/text/binary fidelity");}
            target.rollback();check(TargetValidation.count(target,"tb_target.bulk_items",Engine.MYSQL)==0,"native bulk never commits caller transaction");
            RowCopier.execute(target,"CREATE TABLE tb_source.bulk_scalars(id bigint PRIMARY KEY, text_value longtext, created datetime(6), enabled boolean, bytes_value varbinary(64))");
            String longText="中文🙂'\\".repeat(10000);
            try(PreparedStatement insert=source.prepareStatement("INSERT INTO tb_source.bulk_scalars VALUES(?,?,?,?,?)")){
                for(int i=0;i<429;i++){insert.setLong(1,i);insert.setString(2,i==0?longText:i%2==0?null:"row"+i);insert.setTimestamp(3,Timestamp.valueOf("2026-09-30 12:34:56.123456"));insert.setBoolean(4,i%2==0);insert.setBytes(5,new byte[]{0,1,(byte)255});insert.addBatch();}insert.executeBatch();source.commit();
            }
            RowCopier.execute(target,"CREATE TABLE tb_target.bulk_scalars LIKE tb_source.bulk_scalars");
            var fields=List.of("id","text_value","created","enabled","bytes_value");
            check(RowCopier.copy(src,dst,Engine.MYSQL,"tb_source.bulk_scalars","tb_target.bulk_scalars",fields,false,()->{},v->{})==429,"native bulk 429 scalar rows copied");target.commit();
            try(Statement q=target.createStatement();ResultSet rs=q.executeQuery("SELECT text_value,created,enabled,hex(bytes_value) FROM tb_target.bulk_scalars WHERE id=0")){
                check(rs.next()&&longText.equals(rs.getString(1)),"bulk text not preview-truncated");check(rs.getTimestamp(2).getNanos()==123456000&&rs.getBoolean(3)&&rs.getString(4).equals("0001FF"),"bulk timestamps booleans and binary preserved");
            }
            boolean rejected=false;try{RowCopier.copy(src,dst,Engine.MYSQL,"tb_source.bulk_scalars","tb_target.bulk_scalars",fields,false,()->{},v->{});}catch(SQLException expected){rejected=true;target.rollback();}
            check(rejected&&TargetValidation.count(target,"tb_target.bulk_scalars",Engine.MYSQL)==429,"native bulk duplicate failure propagated without changing committed rows");

        }finally{control.detach();}
    }
    static long sessionInserts(Connection connection)throws SQLException{
        try(Statement query=connection.createStatement();ResultSet rows=query.executeQuery("SHOW SESSION STATUS LIKE 'Com_insert'")){if(!rows.next())throw new SQLException("No INSERT counter");return rows.getLong(2);}
    }
    static Object countRemote(Object value,java.util.concurrent.atomic.AtomicInteger calls){
        Class<?> api=value instanceof com.intellij.database.remote.jdbc.RemoteConnection?com.intellij.database.remote.jdbc.RemoteConnection.class:
            value instanceof com.intellij.database.remote.jdbc.RemoteBatchPreparedStatement?com.intellij.database.remote.jdbc.RemoteBatchPreparedStatement.class:
            value instanceof com.intellij.database.remote.jdbc.RemotePreparedStatement?com.intellij.database.remote.jdbc.RemotePreparedStatement.class:
            value instanceof com.intellij.database.remote.jdbc.RemoteStatement?com.intellij.database.remote.jdbc.RemoteStatement.class:
            value instanceof com.intellij.database.remote.jdbc.RemoteResultSet?com.intellij.database.remote.jdbc.RemoteResultSet.class:
            value instanceof com.intellij.database.remote.jdbc.RemoteResultSetMetaData?com.intellij.database.remote.jdbc.RemoteResultSetMetaData.class:null;
        if(api==null)return value;
        return java.lang.reflect.Proxy.newProxyInstance(MySqlIntegration.class.getClassLoader(),new Class<?>[]{api},(p,m,a)->{
            calls.incrementAndGet();try{return countRemote(m.invoke(value,a),calls);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });
    }
    static void testTemporalDefaults(Connection target)throws Exception{
        boolean reproduced=false;
        try{RowCopier.execute(target,"CREATE TABLE tb_target.old_default_bug(create_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP)");}
        catch(SQLException expected){reproduced=expected.getErrorCode()==1067;}
        check(reproduced,"old datetime/default precision mismatch reproduces error 1067");
        int index=0;
        for(int precision=0;precision<=6;precision++)for(String def:List.of("now()","CURRENT_TIMESTAMP","CURRENT_TIMESTAMP(0)","CURRENT_TIMESTAMP(3)","CURRENT_TIMESTAMP(6)","localtimestamp","transaction_timestamp()")){
            String table="temporal_"+index++;
            var column=new CrossEngineDdl.Column("create_time","timestamp("+precision+") without time zone",Types.OTHER,0,0,true,false,def,false);
            var ddl=CrossEngineDdl.build(Engine.POSTGRES,Engine.MYSQL,"tb_target",table,List.of(column),List.of());
            RowCopier.execute(target,ddl.sql());
            RowCopier.execute(target,"INSERT INTO tb_target."+table+" () VALUES ()");
            try(Statement statement=target.createStatement();ResultSet rs=statement.executeQuery("SELECT create_time FROM tb_target."+table)){
                check(rs.next()&&rs.getTimestamp(1)!=null,"timestamp default executes: "+column.type()+" / "+def);
            }
        }
        var literal=new CrossEngineDdl.Column("create_time","timestamp",Types.OTHER,0,0,true,false,"'2026-09-30 12:34:56.123456'::timestamp without time zone",false);
        RowCopier.execute(target,CrossEngineDdl.build(Engine.POSTGRES,Engine.MYSQL,"tb_target","literal_time",List.of(literal),List.of()).sql());
        RowCopier.execute(target,"INSERT INTO tb_target.literal_time () VALUES ()");
        try(Statement statement=target.createStatement();ResultSet rs=statement.executeQuery("SELECT create_time FROM tb_target.literal_time")){
            check(rs.next()&&rs.getTimestamp(1).getNanos()==123456000,"literal timestamp microseconds preserved");
        }
    }
    static void testCancellation(String url)throws Exception{
        TransferControl control=new TransferControl();try(Connection raw=DriverManager.getConnection(url,"root","");Statement s=control.wrap(raw).createStatement()){
            CompletableFuture<Boolean> operation=CompletableFuture.supplyAsync(()->{try(ResultSet rs=s.executeQuery("SELECT SLEEP(20)")){rs.next();return rs.getInt(1)==1;}catch(SQLException expected){throw new CompletionException(expected);}});
            Thread.sleep(250);control.cancel();boolean stopped=false;try{stopped=operation.get(5,TimeUnit.SECONDS);}catch(ExecutionException expected){stopped=true;}check(stopped,"window cancellation interrupts active real SQL");
        }finally{control.detach();}
    }
}
