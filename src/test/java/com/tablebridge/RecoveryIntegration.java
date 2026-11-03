package com.tablebridge;
import java.sql.*;
import java.util.*;
import java.lang.reflect.*;

/** Real target-session termination and ambiguous commit acknowledgement, task-owned endpoints only. */
final class RecoveryIntegration {
    static void run(Connection source,Engine e)throws Exception{
        String from=e.qualified("tb_matrix","network_source"),to=e.qualified("tb_matrix","network_target");
        RowCopier.execute(source,"CREATE TABLE "+from+"(id integer PRIMARY KEY,note varchar(40))");RowCopier.execute(source,"CREATE TABLE "+to+"(id integer PRIMARY KEY,note varchar(40))");
        try(var p=source.prepareStatement("INSERT INTO "+from+" VALUES(?,?)")){for(int i=1;i<=601;i++){p.setInt(1,i);p.setString(2,"resume-"+i);p.addBatch();}p.executeBatch();}
        RowCopier.execute(source,"INSERT INTO "+to+" VALUES(900,'existing')");
        String url=source.getMetaData().getURL(),user=e==Engine.MYSQL?"root":System.getProperty("user.name"),jobId=UUID.randomUUID().toString();
        var spec=new ResumableTable.Spec(from,to,List.of("id","note"),List.of("id"),List.of(Types.INTEGER,Types.VARCHAR),false,ExistingPolicy.APPEND);
        try(Connection interrupted=DriverManager.getConnection(url,user,"")){
            setup(interrupted,e);long backend;try(Statement s=interrupted.createStatement();ResultSet r=s.executeQuery(e==Engine.MYSQL?"SELECT CONNECTION_ID()":"SELECT pg_backend_pid()")){r.next();backend=r.getLong(1);}
            var job=new ResumableTable(interrupted,e,"tb_matrix",jobId,false,"network",()->{},line->{});boolean failed=false;
            try{job.load(0,source,e,spec,n->{if(n==500)try{RowCopier.execute(source,e==Engine.MYSQL?"KILL CONNECTION "+backend:"SELECT pg_terminate_backend("+backend+")");}catch(SQLException x){throw new RuntimeException(x);}});}catch(SQLException expected){failed=true;}
            CrossIntegration.check(failed,"real terminated target session interrupts transfer "+e);
        }catch(SQLException closed){/* terminated session cleanup may report its closed connection */}
        try(Connection target=DriverManager.getConnection(url,user,"")){
            setup(target,e);var job=new ResumableTable(target,e,"tb_matrix",jobId,true,"network",()->{},line->{});
            CrossIntegration.check(job.get(0).rows()==500,"server checkpoint survives terminated session "+e);
            CrossIntegration.check(TargetValidation.count(target,to,e)==1,"interruption before apply leaves destination unchanged "+e);
            CrossIntegration.check(job.load(0,source,e,spec,n->{})==601,"reconnection resumes remaining rows "+e);
            job.apply(0,spec,false);
            Connection ambiguous=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{try{Object result=method.invoke(target,args);if(method.getName().equals("commit"))throw new SQLRecoverableException("injected lost commit acknowledgement","08006");return result;}catch(InvocationTargetException ex){throw ex.getCause();}});
            try{ambiguous.commit();throw new AssertionError("commit acknowledgement injection missing");}catch(SQLRecoverableException expected){CrossIntegration.check(DurableRunner.transientFailure(expected),"commit acknowledgement classified recoverable "+e);}
        }
        try(Connection target=DriverManager.getConnection(url,user,"")){
            setup(target,e);var job=new ResumableTable(target,e,"tb_matrix",jobId,true,"network",()->{},line->{});
            Process worker=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-ea","-cp",System.getProperty("java.class.path"),"com.tablebridge.RecoveryWorker",e.name(),jobId).redirectErrorStream(true).redirectOutput(java.nio.file.Path.of("build","recovery-worker-"+e+".log").toFile()).start();
            CrossIntegration.check(worker.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)&&worker.exitValue()==0,"independent JVM reloads committed checkpoint "+e);
            CrossIntegration.check(job.apply(0,spec,false)==0&&TargetValidation.count(target,to,e)==602,"fresh connection resolves committed marker without duplicate append "+e);target.rollback();job.cleanup(List.of(0));
        }
    }
    static void nativeStage(Connection source,Engine e)throws Exception{
        String to=e.qualified("tb_matrix","native_stage_target");RowCopier.execute(source,"CREATE TABLE "+to+"(id integer PRIMARY KEY,note varchar(40))");
        try(Connection raw=DriverManager.getConnection(source.getMetaData().getURL(),e==Engine.MYSQL?"root":System.getProperty("user.name"),"")){
            setup(raw,e);Connection api=CrossIntegration.remote(raw,e),read=CrossIntegration.remote(source,e);DurableRunner.requirePrimaryKey(api,e,"tb_matrix","native_stage_target",List.of("id"));
            var job=new ResumableTable(api,e,"tb_matrix",UUID.randomUUID().toString(),false,"native-stage",()->{},line->{});var spec=new ResumableTable.Spec(e.qualified("tb_matrix","network_source"),to,List.of("id","note"),List.of("id"),List.of(Types.INTEGER,Types.VARCHAR),false,ExistingPolicy.FAIL);
            CrossIntegration.check(job.load(0,read,e,spec,n->{})==601,"native driver staged transfer including bounded MySQL multirow writes "+e);job.apply(0,spec,false);job.verify(0,spec);api.commit();CrossIntegration.check(TargetValidation.count(api,to,e)==601,"native driver checkpoint and content verification "+e);job.cleanup(List.of(0));
        }
    }
    static void setup(Connection c,Engine e)throws SQLException{if(e==Engine.MYSQL)c.setCatalog("tb_matrix");else RowCopier.execute(c,"SET search_path TO tb_matrix");c.setAutoCommit(false);}
}
