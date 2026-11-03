package com.tablebridge;

import java.sql.*;
import java.util.*;
import java.util.function.LongConsumer;

/** Streams one table into a freshly created target; a caller-owned transaction wraps each table. */
public final class RowCopier {
    static final int BATCH_SIZE=250;
    private RowCopier(){}
    public static void checkTypes(ResultSetMetaData meta)throws SQLException{
        checkTypes(meta,List.of());
    }
    static void checkTypes(ResultSetMetaData meta,List<Integer> targetTypes)throws SQLException{
        checkTypes(meta,targetTypes,null,null);
    }
    static void checkTypes(ResultSetMetaData meta,List<Integer> targetTypes,Engine from,Engine to)throws SQLException{
        if(!targetTypes.isEmpty()&&targetTypes.size()!=meta.getColumnCount())throw new SQLException("字段映射数量与来源列数不一致");
        for(int i=1;i<=meta.getColumnCount();i++) {
            int type=meta.getColumnType(i);
            if(!targetTypes.isEmpty()&&Set.of(Types.VARCHAR,Types.CHAR).contains(targetTypes.get(i-1)))continue;
            if(ZonedValues.timestamp(meta.getColumnTypeName(i))&&!targetTypes.isEmpty()&&targetTypes.get(i-1)!=Types.NULL&&targetTypes.get(i-1)!=Types.TIMESTAMP_WITH_TIMEZONE)throw new SQLException("带时区字段必须映射为带时区时间或文本："+meta.getColumnName(i));
            if(ZonedValues.time(meta.getColumnTypeName(i))&&!targetTypes.isEmpty()&&targetTypes.get(i-1)!=Types.NULL&&targetTypes.get(i-1)!=Types.TIME_WITH_TIMEZONE)throw new SQLException("带时区 TIME 必须映射为带时区 TIME 或文本："+meta.getColumnName(i));
            if(from==Engine.POSTGRES&&to==Engine.POSTGRES&&SpecialValues.postgresNative(type))continue;
            if(Set.of(Types.ARRAY,Types.STRUCT,Types.REF,Types.JAVA_OBJECT,Types.SQLXML,Types.DATALINK,Types.DISTINCT).contains(type))
                throw new SQLFeatureNotSupportedException("尚不支持记录类型 "+meta.getColumnTypeName(i)+"："+meta.getColumnName(i));
        }
    }
    public static long copy(Connection source,Connection target,Engine engine,String sourceTable,String targetTable,List<String> columns,boolean identity,Runnable checkCancelled,LongConsumer progress)throws SQLException {
        return copy(source,target,engine,engine,sourceTable,targetTable,columns,identity,List.of(),checkCancelled,progress);
    }
    public static long copy(Connection source,Connection target,Engine sourceEngine,Engine engine,String sourceTable,String targetTable,List<String> columns,boolean identity,List<Integer> targetTypes,Runnable checkCancelled,LongConsumer progress)throws SQLException {
        return copy(source,target,sourceEngine,engine,sourceTable,targetTable,columns,identity,targetTypes,checkCancelled,progress,message->{});
    }
    public static long copy(Connection source,Connection target,Engine sourceEngine,Engine engine,String sourceTable,String targetTable,List<String> columns,boolean identity,List<Integer> targetTypes,Runnable checkCancelled,LongConsumer progress,java.util.function.Consumer<String> diagnostics)throws SQLException {
        String sourceFields=sourceEngine==Engine.ORACLE?SpecialValues.oracleFields(source,sourceTable,columns):String.join(", ",columns.stream().map(sourceEngine::quote).toList());
        String fields=String.join(", ",columns.stream().map(engine::quote).toList());
        String override=engine==Engine.POSTGRES&&identity?" OVERRIDING SYSTEM VALUE":"";
        String insert="INSERT INTO "+targetTable+" ("+fields+")"+override+" VALUES ("+String.join(", ",Collections.nCopies(columns.size(),"?"))+")";
        boolean identityEnabled=false;
        try {
            if(engine==Engine.MSSQL&&identity){execute(target,"SET IDENTITY_INSERT "+targetTable+" ON");identityEnabled=true;}
            try(Statement query=source.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
                query.setFetchSize(sourceEngine==Engine.MYSQL?Integer.MIN_VALUE:BATCH_SIZE);query.setQueryTimeout(60);
                try(ResultSet rawRows=query.executeQuery("SELECT "+sourceFields+" FROM "+sourceTable)){
                    ResultSetMetaData meta=rawRows.getMetaData();checkTypes(meta,targetTypes,sourceEngine,engine);int count=meta.getColumnCount();long total=0;int pending=0;
                    int[] types=new int[count],precision=new int[count];
                    for(int i=0;i<count;i++){types[i]=ZonedValues.timestamp(meta.getColumnTypeName(i+1))?Types.TIMESTAMP_WITH_TIMEZONE:ZonedValues.time(meta.getColumnTypeName(i+1))?Types.TIME_WITH_TIMEZONE:meta.getColumnType(i+1);if(sourceEngine==Engine.ORACLE&&types[i]==Types.DATE)types[i]=Types.TIMESTAMP;if(types[i]==Types.BIT)precision[i]=meta.getPrecision(i+1);}
                    if(engine==Engine.ORACLE){List<String> placeholders=new ArrayList<>();for(int i=0;i<count;i++){int type=targetTypes.isEmpty()||targetTypes.get(i)==Types.NULL?types[i]:targetTypes.get(i);placeholders.add(type==Types.TIMESTAMP_WITH_TIMEZONE?"TO_TIMESTAMP_TZ(?, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9 TZH:TZM')":"?");}insert="INSERT INTO "+targetTable+" ("+fields+") VALUES ("+String.join(", ",placeholders)+")";}
                    java.util.List<com.intellij.database.remote.jdbc.ColumnInfo> infos=new ArrayList<>();
                    for(int i=0;i<count;i++){int t=targetTypes.isEmpty()||targetTypes.get(i)==Types.NULL?types[i]:targetTypes.get(i);infos.add(new com.intellij.database.remote.jdbc.ColumnInfo("", "",t,i));}
                    boolean bulkWrite=engine==Engine.MYSQL&&target instanceof RemoteBulk.ConnectionApi&&infos.stream().allMatch(c->RemoteBulk.supported(c.getType()));
                    boolean bulkRead=(sourceEngine==Engine.POSTGRES||sourceEngine==Engine.MYSQL)&&rawRows instanceof RemoteBulk.RowsApi&&Arrays.stream(types).allMatch(RemoteBulk::supported);
                    diagnostics.accept("数据通道：读取="+(bulkRead?"驱动整批读取":"逐字段读取")+"；写入="+(bulkWrite?"MySQL 多行 INSERT（每批最多 "+BATCH_SIZE+" 行）":"JDBC 批处理")+"；字段类型="+Arrays.toString(types));
                    try(PreparedStatement write=bulkWrite?((RemoteBulk.ConnectionApi)target).prepareBulk(insert,infos):target.prepareStatement(insert);
                        ResultSet rows=(sourceEngine==Engine.POSTGRES||sourceEngine==Engine.MYSQL)?RemoteBulk.buffered(rawRows,types):rawRows){
                    write.setQueryTimeout(60);
                    while(rows.next()){
                        checkCancelled.run();
                        for(int i=1;i<=count;i++){
                            int targetType=targetTypes.isEmpty()?Types.NULL:targetTypes.get(i-1);
                            bindColumn(rows,write,types[i-1],precision[i-1],i,sourceEngine,engine,targetType,columns.get(i-1));
                        }
                        write.addBatch();pending++;total++;
                        if(pending==BATCH_SIZE){batch(write);pending=0;progress.accept(total);}
                    }
                    if(pending>0)batch(write);progress.accept(total);return total;
                    }
                }
            }
        }finally{if(identityEnabled)execute(target,"SET IDENTITY_INSERT "+targetTable+" OFF");}
    }
    static void bindColumn(ResultSet rows,PreparedStatement write,int type,int precision,int i,Engine sourceEngine,Engine engine,int targetType,String column)throws SQLException{
        if(TargetValidation.numeric(targetType)&&(type==Types.BOOLEAN||(type==Types.BIT&&precision<=1))){boolean value=rows.getBoolean(i);if(rows.wasNull())write.setNull(i,targetType);else write.setBigDecimal(i,value?java.math.BigDecimal.ONE:java.math.BigDecimal.ZERO);}
        else if(targetType==Types.BOOLEAN&&TargetValidation.numeric(type)){var value=rows.getBigDecimal(i);if(value==null)write.setNull(i,targetType);else if(value.compareTo(java.math.BigDecimal.ZERO)==0||value.compareTo(java.math.BigDecimal.ONE)==0)write.setBoolean(i,value.signum()!=0);else throw new SQLException("布尔值必须是 0 或 1，字段 "+column);}
        else if(engine==Engine.POSTGRES&&targetType==Types.TIME_WITH_TIMEZONE){String value=rows.getString(i);if(value==null)write.setNull(i,Types.OTHER);else write.setObject(i,ZonedValues.parseTime(value).toString(),Types.OTHER);}
        else if(engine==Engine.POSTGRES&&targetType==Types.TIMESTAMP_WITH_TIMEZONE){ZonedValues.bind(rows,write,i,targetType);}
        else if(targetType==Types.OTHER){String value=rows.getString(i);if(value==null)write.setNull(i,targetType);else write.setObject(i,value,targetType);}
        else if(sourceEngine!=engine&&(targetType==Types.VARCHAR||targetType==Types.CHAR)){String value=rows.getString(i);if(value==null)write.setNull(i,targetType);else write.setString(i,value);}
        else if(engine==Engine.ORACLE&&(targetType==Types.TIMESTAMP_WITH_TIMEZONE||targetType==Types.NULL&&type==Types.TIMESTAMP_WITH_TIMEZONE)){String text=rows.getString(i);if(text==null)write.setNull(i,Types.VARCHAR);else write.setString(i,ZonedValues.oracleText(text));}
        else bind(rows,write,type,precision,i,engine);
    }
    private static void batch(PreparedStatement s)throws SQLException{for(int count:s.executeBatch())if(count==Statement.EXECUTE_FAILED)throw new SQLException("批量写入失败");s.clearBatch();}
    private static void bind(ResultSet rs,PreparedStatement ps,int type,int precision,int i,Engine engine)throws SQLException{
        // Retrieve transportable values, never vendor-specific driver objects across JVMs.
        switch(type){
            case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB -> {byte[] v=rs.getBytes(i);if(v==null)ps.setNull(i,type);else ps.setBytes(i,v);}
            case Types.NUMERIC,Types.DECIMAL,Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT -> {var v=rs.getBigDecimal(i);if(v==null)ps.setNull(i,type);else ps.setBigDecimal(i,v);}
            case Types.FLOAT,Types.DOUBLE,Types.REAL -> {double v=rs.getDouble(i);if(rs.wasNull())ps.setNull(i,type);else ps.setDouble(i,v);}
            case Types.BOOLEAN -> {boolean v=rs.getBoolean(i);if(rs.wasNull())ps.setNull(i,type);else ps.setBoolean(i,v);}
            case Types.BIT -> {if(precision>1){byte[] v=rs.getBytes(i);if(v==null)ps.setNull(i,type);else ps.setBytes(i,v);}else{boolean v=rs.getBoolean(i);if(rs.wasNull())ps.setNull(i,type);else ps.setBoolean(i,v);}}
            case Types.DATE -> {var v=rs.getDate(i);if(v==null)ps.setNull(i,type);else ps.setDate(i,v);}
            case Types.TIME -> {String v=rs.getString(i);if(v==null)ps.setNull(i,type);else if(engine==Engine.POSTGRES){try{ps.setObject(i,java.time.LocalTime.parse(v),Types.TIME);}catch(java.time.DateTimeException ex){throw new SQLException("TIME 超出 PostgreSQL 一日时间范围，字段位置："+i,ex);}}else ps.setString(i,v);}
            case Types.TIMESTAMP -> {var v=rs.getTimestamp(i);if(v==null)ps.setNull(i,type);else ps.setTimestamp(i,v);}
            case Types.TIMESTAMP_WITH_TIMEZONE -> ZonedValues.bind(rs,ps,i,type);
            case Types.ARRAY,Types.STRUCT,Types.SQLXML,Types.DISTINCT -> {if(engine!=Engine.POSTGRES)throw new SQLFeatureNotSupportedException("复杂值须明确文本映射");String v=rs.getString(i);if(v==null)ps.setNull(i,Types.OTHER);else ps.setObject(i,v,Types.OTHER);}
            case Types.TIME_WITH_TIMEZONE -> {String v=rs.getString(i);if(v==null)ps.setNull(i,Types.OTHER);else ps.setObject(i,ZonedValues.parseTime(v).toString(),Types.OTHER);}
            case Types.OTHER -> {String v=rs.getString(i);if(v==null)ps.setNull(i,type);else ps.setObject(i,v,type);}
            default -> {String v=rs.getString(i);if(v==null)ps.setNull(i,type);else ps.setString(i,v);}
        }
    }
    static void execute(Connection c,String sql)throws SQLException{try(Statement s=c.createStatement()){s.setQueryTimeout(60);s.execute(sql);}}
}
