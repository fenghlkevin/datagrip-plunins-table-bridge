package com.tablebridge;

import java.sql.*;
import java.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** Only key values go in the target checkpoint, never local files or log messages. */
final class KeyCursor {
    record Value(int type,String text){}
    static List<Value> read(ResultSet rs,List<Integer> positions,int[] types)throws SQLException{
        List<Value> keys=new ArrayList<>();for(int index:positions){int type=types[index-1];String value;
            if(Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY).contains(type)){byte[] bytes=rs.getBytes(index);value=bytes==null?null:Base64.getEncoder().encodeToString(bytes);}
            else if(TargetValidation.numeric(type)){BigDecimal n=rs.getBigDecimal(index);value=n==null?null:n.toPlainString();}
            else value=rs.getString(index);
            if(value==null)throw new SQLException("断点主键含 NULL");if(value.length()>65536)throw new SQLException("主键超过检查点大小限制");keys.add(new Value(type,value));
        }return List.copyOf(keys);
    }
    static String encode(List<Value> values){StringJoiner out=new StringJoiner(";");for(Value v:values)out.add(v.type+":"+Base64.getEncoder().encodeToString(v.text.getBytes(StandardCharsets.UTF_8)));return out.toString();}
    static List<Value> decode(String text){if(text==null||text.isEmpty())return List.of();if(text.length()>1048576)throw new IllegalArgumentException("检查点过大");List<Value> result=new ArrayList<>();for(String v:text.split(";")){int split=v.indexOf(':');if(split<1)throw new IllegalArgumentException("检查点无效");result.add(new Value(Integer.parseInt(v.substring(0,split)),new String(Base64.getDecoder().decode(v.substring(split+1)),StandardCharsets.UTF_8)));}return List.copyOf(result);}
    static String after(Engine engine,List<String> keys){List<String> terms=new ArrayList<>();for(int i=0;i<keys.size();i++){List<String> parts=new ArrayList<>();for(int j=0;j<i;j++)parts.add(engine.quote(keys.get(j))+"=?");parts.add(engine.quote(keys.get(i))+">?");terms.add("("+String.join(" AND ",parts)+")");}return "("+String.join(" OR ",terms)+")";}
    static void bind(PreparedStatement query,List<Value> keys)throws SQLException{int position=1;for(int i=0;i<keys.size();i++)for(int j=0;j<=i;j++)bindValue(query,position++,keys.get(j));}
    static void bindValue(PreparedStatement s,int position,Value v)throws SQLException{switch(v.type){
        case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY->s.setBytes(position,Base64.getDecoder().decode(v.text));
        case Types.DECIMAL,Types.NUMERIC,Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT->s.setBigDecimal(position,new BigDecimal(v.text));
        case Types.DATE->s.setDate(position,java.sql.Date.valueOf(v.text));
        case Types.TIMESTAMP->s.setTimestamp(position,Timestamp.valueOf(v.text.replace('T',' ')));
        case Types.TIMESTAMP_WITH_TIMEZONE->s.setObject(position,ZonedValues.parse(v.text),Types.TIMESTAMP_WITH_TIMEZONE);
        case Types.BOOLEAN,Types.BIT->s.setBoolean(position,v.text.equals("1")||Boolean.parseBoolean(v.text));
        case Types.TIME_WITH_TIMEZONE->s.setObject(position,v.text,Types.OTHER);
        case Types.TIME->s.setTime(position,java.sql.Time.valueOf(v.text));
        case Types.OTHER->s.setObject(position,v.text,Types.OTHER);
        default->s.setString(position,v.text);
    }}
}
