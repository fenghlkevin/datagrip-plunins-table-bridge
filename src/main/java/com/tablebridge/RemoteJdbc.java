package com.tablebridge;

import com.intellij.database.remote.jdbc.*;
import java.lang.reflect.*;
import java.sql.*;

/** Adapts the small JDBC surface used by the transfer engine to DataGrip's driver process. */
final class RemoteJdbc {
    static Connection connection(RemoteConnection c){return wrap(c,Connection.class);}
    @SuppressWarnings("unchecked")
    private static <T> T wrap(Object remote,Class<T> api){
        return (T)Proxy.newProxyInstance(RemoteJdbc.class.getClassLoader(),api==Connection.class?new Class<?>[]{api,RemoteBulk.ConnectionApi.class}:api==ResultSet.class?new Class<?>[]{api,RemoteBulk.RowsApi.class}:new Class<?>[]{api},(proxy,method,args)->{
            if(method.getName().equals("toString"))return "TableBridge remote "+api.getSimpleName();
            if(method.getName().equals("equals"))return proxy==args[0];
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("unwrap"))throw new SQLFeatureNotSupportedException();
            if(method.getName().equals("isWrapperFor"))return false;
            try {
                if(method.getName().equals("prepareBulk"))return RemoteBulk.prepare((RemoteConnection)remote,(String)args[0],(java.util.List<ColumnInfo>)args[1]);
                if(method.getName().equals("fetchRows"))return ((RemoteResultSet)remote).getObjects(new RemoteResultSet.DataRetrievingOptions((Integer)args[0],Integer.MAX_VALUE,true));
                Object value=remote.getClass().getMethod(method.getName(),method.getParameterTypes()).invoke(remote,args);
                if(value instanceof RemotePreparedStatement x)return wrap(x,PreparedStatement.class);
                if(value instanceof RemoteStatement x)return wrap(x,Statement.class);
                if(value instanceof RemoteResultSet x)return wrap(x,ResultSet.class);
                if(value instanceof RemoteResultSetMetaData x)return wrap(x,ResultSetMetaData.class);
                if(value instanceof RemoteConnection x)return wrap(x,Connection.class);
                if(value instanceof RemoteDatabaseMetaData x)return wrap(x,DatabaseMetaData.class);
                return value;
            }catch(InvocationTargetException ex){Throwable cause=ex.getCause();if(cause instanceof SQLException)throw cause;throw new SQLException("数据库驱动调用失败："+method.getName(),cause);}
            catch(ReflectiveOperationException ex){throw new SQLFeatureNotSupportedException(method.getName(),ex);}
        });
    }
}
