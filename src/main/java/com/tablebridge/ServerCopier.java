package com.tablebridge;

import java.sql.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Copies within a verified single data source; never retries a partially executed insert. */
final class ServerCopier {
    static String sql(Engine engine,String source,String target,List<String> columns,boolean identity){
        if(columns.isEmpty())throw new IllegalArgumentException("没有可复制的字段");
        String fields=String.join(", ",columns.stream().map(engine::quote).toList());
        return "INSERT INTO "+target+" ("+fields+")"
            +(engine==Engine.POSTGRES&&identity?" OVERRIDING SYSTEM VALUE":"")
            +" SELECT "+fields+" FROM "+source;
    }
    static long copy(Connection connection,Engine engine,String source,String target,List<String> columns,
                     boolean identity,BooleanSupplier cancelled)throws SQLException{
        return copy(connection,engine,source,target,columns,identity,cancelled,0);
    }
    static long copy(Connection connection,Engine engine,String source,String target,List<String> columns,boolean identity,BooleanSupplier cancelled,long baseline)throws SQLException{
        boolean enabled=false;int inserted=-1;
        try {
            if(engine==Engine.MSSQL&&identity){RowCopier.execute(connection,"SET IDENTITY_INSERT "+target+" ON");enabled=true;}
            try(Statement statement=connection.createStatement()){
                ScheduledExecutorService watcher=Executors.newSingleThreadScheduledExecutor(r->{
                    Thread t=new Thread(r,"TableBridge SQL cancellation");t.setDaemon(true);return t;
                });
                try{
                    if(cancelled.getAsBoolean())throw new com.intellij.openapi.progress.ProcessCanceledException();
                    watcher.scheduleWithFixedDelay(()->{
                        if(cancelled.getAsBoolean())try{statement.cancel();}catch(SQLException ignored){}
                    },200,200,TimeUnit.MILLISECONDS);
                    // No arbitrary 60-second cutoff for large server-side table copies.
                    inserted=statement.executeUpdate(sql(engine,source,target,columns,identity));
                    if(cancelled.getAsBoolean())throw new com.intellij.openapi.progress.ProcessCanceledException();
                }catch(SQLException ex){
                    if(cancelled.getAsBoolean())throw new com.intellij.openapi.progress.ProcessCanceledException();
                    throw ex;
                }finally{
                    watcher.shutdownNow();
                    try{watcher.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
                }
            }
            // JDBC update counts can overflow int for large tables; obtain a long from the fresh target.
            try(Statement count=connection.createStatement();ResultSet rs=count.executeQuery("SELECT "+(engine==Engine.MSSQL?"COUNT_BIG(*)":"COUNT(*)")+" FROM "+target)){
                rs.next();long rows=rs.getLong(1)-baseline;if(rows<0)throw new SQLException("目标行数异常减少");if(inserted>=0&&rows!=inserted)throw new SQLException("目标行数校验失败");return rows;
            }
        }finally{if(enabled)RowCopier.execute(connection,"SET IDENTITY_INSERT "+target+" OFF");}
    }
}
