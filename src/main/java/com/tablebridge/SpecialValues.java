package com.tablebridge;

import java.sql.*;
import java.util.*;

/** Preserve PostgreSQL's own textual input/output for native composite/array/XML values. */
final class SpecialValues {
    static boolean postgresNative(int type){return Set.of(Types.ARRAY,Types.STRUCT,Types.SQLXML,Types.DISTINCT).contains(type);}
    static String oracleFields(Connection source,String table,List<String> columns)throws SQLException{
        try(Statement s=source.createStatement();ResultSet rs=s.executeQuery("SELECT "+String.join(",",columns.stream().map(Engine.ORACLE::quote).toList())+" FROM "+table+" WHERE 1=0")){
            List<String> fields=new ArrayList<>();for(int i=0;i<columns.size();i++){String name=Engine.ORACLE.quote(columns.get(i)),nativeType=rs.getMetaData().getColumnTypeName(i+1).toLowerCase(Locale.ROOT);fields.add(nativeType.contains("local time zone")?"CAST("+name+" AS TIMESTAMP WITH TIME ZONE) AS "+name:name);}return String.join(",",fields);
        }
    }
    static void oracleSession(Connection c)throws SQLException{
        RowCopier.execute(c,"ALTER SESSION SET TIME_ZONE = '+00:00'");
        RowCopier.execute(c,"ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD\"T\"HH24:MI:SS'");
        RowCopier.execute(c,"ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9'");
        RowCopier.execute(c,"ALTER SESSION SET NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9 TZH:TZM'");
    }
}
