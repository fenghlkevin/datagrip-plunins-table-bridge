package com.tablebridge;

import java.sql.*;
import java.util.*;

final class TargetValidation {
    record Shape(List<Integer> types,boolean identity){}
    static void distinct(Connection source,Connection target,TransferPlan plan)throws SQLException{
        if(plan.engine!=plan.targetEngine)return;
        boolean possible=plan.sourceNamespace.getName().equalsIgnoreCase(plan.targetNamespace.getName())
            && plan.items.stream().anyMatch(i->i.source().getName().equalsIgnoreCase(i.targetName()));
        if(!possible)return;
        String query=switch(plan.engine){
            case MYSQL->"SELECT @@server_uuid";
            case POSTGRES->"SELECT system_identifier::text || ':' || current_database() FROM pg_control_system()";
            case MSSQL->"SELECT CAST(SERVERPROPERTY('ServerName') AS nvarchar(256)) + ':' + DB_NAME()";
            case ORACLE->"SELECT SYS_CONTEXT('USERENV','DB_UNIQUE_NAME') || ':' || SYS_CONTEXT('USERENV','CON_NAME') FROM dual";
        };
        String a=identity(source,query),b=identity(target,query);if(a==null||b==null||a.equals(b))throw new SQLException("来源和目标可能是同一张表（含连接别名）；请改名或选择不同数据库/模式，禁止自覆盖");
    }
    private static String identity(Connection c,String sql)throws SQLException{try(Statement s=c.createStatement();ResultSet rs=s.executeQuery(sql)){return rs.next()?rs.getString(1):null;}catch(SQLException ex){throw new SQLException("无法验证同名来源与目标的服务器身份；请为目标改名后重试。"+ex.getMessage(),ex);}}
    static Shape existing(Connection target,Engine engine,String table,List<String> columns,ResultSetMetaData source)throws SQLException{
        try(Statement s=target.createStatement();ResultSet rs=s.executeQuery("SELECT "+String.join(",",columns.stream().map(engine::quote).toList())+" FROM "+table+" WHERE 1=0")){
            ResultSetMetaData meta=rs.getMetaData();List<Integer> types=new ArrayList<>();boolean identity=false;
            for(int i=1;i<=columns.size();i++){
                int from=ZonedValues.timestamp(source.getColumnTypeName(i))?Types.TIMESTAMP_WITH_TIMEZONE:source.getColumnType(i),to=ZonedValues.timestamp(meta.getColumnTypeName(i))?Types.TIMESTAMP_WITH_TIMEZONE:ZonedValues.time(meta.getColumnTypeName(i))?Types.TIME_WITH_TIMEZONE:meta.getColumnType(i);types.add(to);identity|=meta.isAutoIncrement(i);
                if(!compatible(from,to))throw new SQLException("目标字段类型不兼容："+columns.get(i-1)+" · "+source.getColumnTypeName(i)+" → "+meta.getColumnTypeName(i));
                if(numeric(from)&&numeric(to)&&source.getPrecision(i)>0&&meta.getPrecision(i)>0){int sourceWhole=source.getPrecision(i)-source.getScale(i),targetWhole=meta.getPrecision(i)-meta.getScale(i);if(sourceWhole>targetWhole||source.getScale(i)>meta.getScale(i))throw new SQLException("目标数值精度不足："+columns.get(i-1));}
                if(text(from)&&text(to)&&meta.getPrecision(i)>0&&source.getPrecision(i)>meta.getPrecision(i))throw new SQLException("目标字符长度不足："+columns.get(i-1));
            }return new Shape(List.copyOf(types),identity);
        }
    }
    static Shape created(Connection target,Engine engine,String table,List<TransferPlan.Column> columns)throws SQLException{
        try(Statement s=target.createStatement();ResultSet rs=s.executeQuery("SELECT "+String.join(",",columns.stream().map(c->engine.quote(c.name())).toList())+" FROM "+table+" WHERE 1=0")){
            ResultSetMetaData meta=rs.getMetaData();List<Integer> types=new ArrayList<>();boolean identity=false;
            for(int i=1;i<=columns.size();i++){
                int actual=ZonedValues.timestamp(meta.getColumnTypeName(i))?Types.TIMESTAMP_WITH_TIMEZONE:ZonedValues.time(meta.getColumnTypeName(i))?Types.TIME_WITH_TIMEZONE:meta.getColumnType(i),expected=columns.get(i-1).targetType();
                if(expected!=Types.NULL&&expected!=Types.OTHER&&!compatible(expected,actual)&&!(expected==Types.BOOLEAN&&Set.of(Types.BIT,Types.TINYINT).contains(actual)))throw new SQLException("完整结构字段类型与字段映射不一致，请同步修改字段映射："+columns.get(i-1).name()+" · JDBC "+expected+" → "+meta.getColumnTypeName(i));
                types.add(actual);identity|=meta.isAutoIncrement(i);
            }
            return new Shape(List.copyOf(types),identity);
        }
    }
    static boolean numeric(int t){return Set.of(Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL).contains(t);}
    static boolean text(int t){return Set.of(Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB).contains(t);}
    static boolean compatible(int a,int b){return a==b||(numeric(a)&&numeric(b))||(text(a)&&text(b))||(Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB).contains(a)&&Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB).contains(b));}
    static void clearAllowed(Connection target,Engine engine,String catalog,String schema,String table)throws SQLException{
        if(engine==Engine.MYSQL){
            try(PreparedStatement s=target.prepareStatement("SELECT ENGINE FROM information_schema.tables WHERE table_schema=? AND table_name=?")){s.setString(1,schema);s.setString(2,table);try(ResultSet rs=s.executeQuery()){if(!rs.next()||!"InnoDB".equalsIgnoreCase(rs.getString(1)))throw new SQLException("清空后复制要求目标表使用 InnoDB 事务引擎");}}
        }
        String sql=switch(engine){
            case MYSQL->"SELECT COUNT(*) FROM information_schema.key_column_usage WHERE referenced_table_schema=? AND referenced_table_name=?";
            case POSTGRES->"SELECT COUNT(*) FROM pg_constraint WHERE contype='f' AND confrelid=?::regclass";
            case MSSQL->"SELECT COUNT(*) FROM sys.foreign_keys WHERE referenced_object_id=OBJECT_ID(?)";
            case ORACLE->"SELECT COUNT(*) FROM all_constraints fk JOIN all_constraints pk ON fk.r_owner=pk.owner AND fk.r_constraint_name=pk.constraint_name WHERE fk.constraint_type='R' AND pk.owner=? AND pk.table_name=?";
        };
        try(PreparedStatement s=target.prepareStatement(sql)){if(engine==Engine.POSTGRES||engine==Engine.MSSQL)s.setString(1,engine.qualified(schema,table));else{s.setString(1,schema);s.setString(2,table);}try(ResultSet rs=s.executeQuery()){if(rs.next()&&rs.getLong(1)>0)throw new SQLException("目标表被外键引用；请先安排依赖表的数据处理，当前不会自动级联清空："+table);}}
    }
    static long count(Connection c,String table,Engine engine)throws SQLException{try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT "+(engine==Engine.MSSQL?"COUNT_BIG(*)":"COUNT(*)")+" FROM "+table)){rs.next();return rs.getLong(1);}}
}
