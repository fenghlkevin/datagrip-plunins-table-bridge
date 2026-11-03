package com.tablebridge;

import com.intellij.database.remote.jdbc.*;
import com.intellij.database.remote.jdbc.impl.RemoteBatchPreparedStatementImpl.Configuration;
import com.intellij.database.remote.dbimport.BatchRecords;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import javax.sql.rowset.*;

/** Bounded bulk transport across the IDE/driver boundary; transaction ownership stays with the caller. */
final class RemoteBulk {
    interface ConnectionApi { PreparedStatement prepareBulk(String sql,List<ColumnInfo> columns)throws SQLException; }
    interface RowsApi { List<Object[]> fetchRows(int count)throws SQLException; }
    static PreparedStatement prepare(RemoteConnection connection,String sql,List<ColumnInfo> columns)throws Exception{
        int values=sql.lastIndexOf(" VALUES (");
        if(values<0)throw new SQLException("批量 INSERT 格式不受支持");
        String prefix=sql.substring(0,values+8),tuple=sql.substring(values+8);
        Map<Integer,RemoteBatchPreparedStatement> statements=new HashMap<>();
        java.util.concurrent.atomic.AtomicReference<RemoteBatchPreparedStatement> active=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        int timeout=60;
        Object[] row=new Object[columns.size()];List<Object[]> batch=new ArrayList<>();int[] queryTimeout={timeout};
        return (PreparedStatement)Proxy.newProxyInstance(RemoteBulk.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            String name=m.getName();
            if(name.equals("equals"))return p==a[0];if(name.equals("hashCode"))return System.identityHashCode(p);
            if(name.equals("setQueryTimeout")){queryTimeout[0]=(Integer)a[0];return null;}
            if(name.equals("cancel")){cancelled.set(true);var statement=active.get();if(statement!=null)statement.cancel();return null;}
            if(name.equals("close")){for(var statement:statements.values())statement.close();statements.clear();return null;}
            if(name.startsWith("set")&&a!=null&&a.length>=2&&a[0] instanceof Integer index){row[index-1]=name.equals("setNull")?null:a[1];return null;}
            if(name.equals("addBatch")){batch.add(row.clone());return null;}
            if(name.equals("clearBatch")){batch.clear();return null;}
            if(name.equals("clearParameters")){Arrays.fill(row,null);return null;}
            if(name.equals("executeBatch")){
                if(cancelled.get())throw new SQLException("批量传输已取消");
                if(batch.isEmpty())return new int[0];
                int size=batch.size();
                String multiSql=prefix+String.join(", ",Collections.nCopies(size,tuple));
                var remote=statements.get(size);
                if(remote==null){remote=connection.prepareBatchStatement(new Configuration(multiSql,sql,null,columns,false,false,true,size));statements.put(size,remote);}
                active.set(remote);if(cancelled.get()){remote.cancel();throw new SQLException("批量传输已取消");}remote.setQueryTimeout(queryTimeout[0]);
                var errors=remote.addBatch(new BatchRecords(new ArrayList<>(batch),0,0),multiSql);
                if(!errors.isEmpty())throw new SQLException("批量传输失败："+errors.getFirst());
                int[] counts=new int[batch.size()];Arrays.fill(counts,Statement.SUCCESS_NO_INFO);batch.clear();return counts;
            }
            if(name.equals("toString"))return "TableBridge multi-row INSERT";
            throw new SQLFeatureNotSupportedException(name);
        });
    }
    static boolean supported(int type){return Set.of(Types.BOOLEAN,Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL,Types.REAL,Types.FLOAT,Types.DOUBLE,Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.DATE,Types.TIMESTAMP).contains(type);}
    static ResultSet buffered(ResultSet source,int[] types)throws SQLException{
        if(!(source instanceof RowsApi bulk)||Arrays.stream(types).anyMatch(t->!supported(t)))return source;
        RowSetMetaDataImpl metadata=new RowSetMetaDataImpl();metadata.setColumnCount(types.length);
        for(int i=0;i<types.length;i++){metadata.setColumnType(i+1,types[i]);metadata.setColumnName(i+1,"c"+i);}
        class Cursor implements InvocationHandler {
            CachedRowSet page;boolean ended;
            public Object invoke(Object p,Method m,Object[] a)throws Throwable{
                if(m.getName().equals("next")){
                    if(page!=null&&page.next())return true;if(ended)return false;
                    if(page!=null)page.close();var values=bulk.fetchRows(128);if(values.isEmpty()){ended=true;return false;}
                    page=RowSetProvider.newFactory().createCachedRowSet();page.setMetaData(metadata);
                    for(Object[] row:values){page.afterLast();page.moveToInsertRow();for(int i=0;i<types.length;i++){
                        Object v=row[i];if(v instanceof java.time.LocalDateTime t)v=Timestamp.valueOf(t);else if(v instanceof java.time.LocalDate d)v=java.sql.Date.valueOf(d);else if(v instanceof java.time.LocalTime t)v=Time.valueOf(t);
                        if(v!=null&&!(v instanceof String||v instanceof Number||v instanceof Boolean||v instanceof byte[]||v instanceof java.util.Date))throw new SQLException("批量读取返回不支持的类型："+v.getClass().getName());
                        page.updateObject(i+1,v);
                    }page.insertRow();page.moveToCurrentRow();}page.beforeFirst();return page.next();
                }
                if(m.getName().equals("close")){if(page!=null)page.close();source.close();return null;}
                try{return m.invoke(m.getName().equals("getMetaData")?source:page,a);}catch(InvocationTargetException e){throw e.getCause();}
            }
        }
        return (ResultSet)Proxy.newProxyInstance(RemoteBulk.class.getClassLoader(),new Class<?>[]{ResultSet.class},new Cursor());
    }
}
