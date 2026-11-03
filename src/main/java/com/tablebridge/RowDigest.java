package com.tablebridge;

import java.sql.*;
import java.util.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.*;

/** Order-independent full-record comparison, including duplicates and NULL positions. */
final class RowDigest {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final BigInteger MODULUS=BigInteger.ONE.shiftLeft(256);
    record Value(long rows,BigInteger sum){Value plus(Value other){return new Value(Math.addExact(rows,other.rows),sum.add(other.sum).mod(MODULUS));}}
    static Value empty(){return new Value(0,BigInteger.ZERO);}
    static Value read(Connection connection,Engine engine,String table,List<String> fields,List<Integer> targetTypes,Runnable cancel)throws SQLException{
        String sql="SELECT "+(engine==Engine.ORACLE?SpecialValues.oracleFields(connection,table,fields):String.join(",",fields.stream().map(engine::quote).toList()))+" FROM "+table;
        return query(connection,engine,sql,fields,targetTypes,cancel);
    }
    static Value query(Connection connection,Engine engine,String sql,List<String> fields,List<Integer> targetTypes,Runnable cancel)throws SQLException{
        MessageDigest hash;try{hash=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
        long count=0;BigInteger sum=BigInteger.ZERO;
        try(Statement query=connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
            query.setFetchSize(engine==Engine.MYSQL?Integer.MIN_VALUE:250);query.setQueryTimeout(60);
            try(ResultSet rows=query.executeQuery(sql)){
                var metadata=rows.getMetaData();if(!targetTypes.isEmpty()&&targetTypes.size()!=fields.size())throw new SQLException("校验字段映射数量不匹配");
                while(rows.next()){
                    cancel.run();hash.reset();
                    for(int i=1;i<=fields.size();i++){
                        int raw=metadata.getColumnType(i),type=targetTypes.isEmpty()||targetTypes.get(i-1)==Types.NULL?raw:targetTypes.get(i-1);
                        if((targetTypes.isEmpty()||targetTypes.get(i-1)==Types.NULL)&&ZonedValues.time(metadata.getColumnTypeName(i)))type=Types.TIME_WITH_TIMEZONE;
                        if(engine==Engine.ORACLE&&type==Types.DATE)type=Types.TIMESTAMP;
                        byte[] value=value(rows,i,raw,type,metadata.getColumnTypeName(i));
                        hash.update((byte)(value==null?0:1));if(value!=null){hash.update(ByteBuffer.allocate(4).putInt(value.length).array());hash.update(value);}
                    }
                    sum=sum.add(new BigInteger(1,hash.digest())).mod(MODULUS);count=Math.addExact(count,1);
                }
            }
        }
        return new Value(count,sum);
    }
    static byte[] value(ResultSet rows,int column,int sourceType,int targetType,String nativeType)throws SQLException{
        String text;
        switch(targetType){
            case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB -> {return rows.getBytes(column);}
            case Types.BOOLEAN,Types.BIT -> {if(sourceType==Types.BOOLEAN||sourceType==Types.BIT){boolean v=rows.getBoolean(column);text=rows.wasNull()?null:v?"1":"0";}else{BigDecimal v=rows.getBigDecimal(column);if(v!=null&&v.compareTo(BigDecimal.ZERO)!=0&&v.compareTo(BigDecimal.ONE)!=0)throw new SQLException("校验发现非 0/1 布尔值");text=v==null?null:v.signum()==0?"0":"1";}}
            case Types.DECIMAL,Types.NUMERIC,Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT -> {BigDecimal value;if(sourceType==Types.BOOLEAN||sourceType==Types.BIT){boolean v=rows.getBoolean(column);value=rows.wasNull()?null:v?BigDecimal.ONE:BigDecimal.ZERO;}else value=rows.getBigDecimal(column);text=value==null?null:value.stripTrailingZeros().toPlainString();}
            case Types.FLOAT,Types.REAL -> {float v=rows.getFloat(column);text=rows.wasNull()?null:Float.toString(v==0?0:v);}
            case Types.DOUBLE -> {double v=rows.getDouble(column);text=rows.wasNull()?null:Double.toString(v==0?0:v);}
            case Types.DATE -> {var v=rows.getDate(column);text=v==null?null:v.toLocalDate().toString();}
            case Types.TIMESTAMP -> {if(ZonedValues.timestamp(nativeType)){String v=rows.getString(column);text=v==null?null:ZonedValues.parse(v).toInstant().toString();}else{var v=rows.getTimestamp(column);text=v==null?null:v.toLocalDateTime().toString();}}
            case Types.TIMESTAMP_WITH_TIMEZONE -> {String v=rows.getString(column);text=v==null?null:ZonedValues.parse(v).toInstant().toString();}
            case Types.TIME_WITH_TIMEZONE -> {String v=rows.getString(column);text=v==null?null:ZonedValues.parseTime(v).toString();}
            case Types.TIME -> {text=rows.getString(column);if(text!=null&&text.contains("."))text=text.replaceFirst("0+$","").replaceFirst("\\.$","");}
            default -> text=rows.getString(column);
        }
        if(text!=null&&targetType!=Types.VARCHAR&&targetType!=Types.CHAR&&Set.of("json","jsonb").contains(nativeType.toLowerCase(Locale.ROOT))){try{text=json(JSON.readTree(text));}catch(java.io.IOException ex){throw new SQLException("JSON 内容校验解析失败",ex);}}
        return text==null?null:text.getBytes(StandardCharsets.UTF_8);
    }
    private static String json(com.fasterxml.jackson.databind.JsonNode node)throws java.io.IOException{
        if(node==null||node.isNull())return "null";
        if(node.isObject()){List<String> names=new ArrayList<>();node.fieldNames().forEachRemaining(names::add);Collections.sort(names);List<String> values=new ArrayList<>();for(String key:names)values.add(JSON.writeValueAsString(key)+":"+json(node.get(key)));return "{"+String.join(",",values)+"}";}
        if(node.isArray()){List<String> values=new ArrayList<>();for(var value:node)values.add(json(value));return "["+String.join(",",values)+"]";}
        if(node.isNumber())return node.decimalValue().stripTrailingZeros().toString();
        return node.toString();
    }
}
