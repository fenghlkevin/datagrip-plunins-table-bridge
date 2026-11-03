package com.tablebridge;

import java.sql.*;
import java.time.*;
import java.util.Set;

/** Offset timestamp transport without JVM-default-zone conversion. */
final class ZonedValues {
    static boolean timestamp(String nativeType){
        if(nativeType==null)return false;
        String type=nativeType.toLowerCase(java.util.Locale.ROOT).replaceAll("\\([0-9]+\\)","").trim();
        return type.equals("timestamptz")||type.equals("timestamp with time zone")||type.equals("timestamp with local time zone");
    }
    static OffsetDateTime parse(String text)throws SQLException{
        try{
            String value=text.trim().replaceFirst(" ([A-Za-z][A-Za-z0-9_+\\-/]*)$", "").replaceFirst("^(\\d{4}-\\d{2}-\\d{2}) ","$1T").replaceFirst(" ([+-]\\d{2}:\\d{2})$","$1");
            value=value.replaceFirst(" ([+-])", "$1").replaceFirst("([+-])(\\d):(\\d{2})$", "$10$2:$3");
            if(value.matches(".*[+-]\\d{2}$"))value+=":00";
            return OffsetDateTime.parse(value);
        }catch(java.time.DateTimeException failure){throw new SQLException("带时区时间格式无法安全转换；请选择明确的文本映射",failure);}
    }
    static boolean time(String nativeType){return nativeType!=null&&Set.of("timetz","time with time zone").contains(nativeType.toLowerCase(java.util.Locale.ROOT).replaceAll("\\([0-9]+\\)","").trim());}
    static OffsetTime parseTime(String text)throws SQLException{try{String value=text.trim().replaceFirst(" ([+-])","$1");if(value.matches(".*[+-]\\d{2}$"))value+=":00";return OffsetTime.parse(value);}catch(java.time.DateTimeException failure){throw new SQLException("带时区 TIME 无法安全转换",failure);}}
    static String oracleText(String text)throws SQLException{
        OffsetDateTime time=parse(text);String offset=time.getOffset().getId().equals("Z")?"+00:00":time.getOffset().getId();
        return time.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS"))+" "+offset;
    }
    static void bind(ResultSet rows,PreparedStatement write,int column,int targetType)throws SQLException{
        String text=rows.getString(column);
        if(text==null)write.setNull(column,Types.TIMESTAMP_WITH_TIMEZONE);
        else write.setObject(column,parse(text),Types.TIMESTAMP_WITH_TIMEZONE);
    }
}
