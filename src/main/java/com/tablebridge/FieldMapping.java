package com.tablebridge;

import java.sql.Types;
import java.util.*;

/** An explicit choice of a target scalar type; no SQL fragments are accepted. */
record FieldMapping(Engine engine,String type,boolean omitDefault,boolean materialize) {
    FieldMapping {
        Objects.requireNonNull(engine);type=type.trim().toLowerCase(Locale.ROOT);
        if(!type.matches("[a-z_]+(?: [a-z_]+)*(?:\\(\\d+(?:\\s*,\\s*\\d+)?(?: char)?\\))?(?: (?:with(?: local)?|without) time zone)?"))throw new IllegalArgumentException("目标类型格式不合法："+type);
        validateParameters(engine,type);
        jdbcType(engine,type); // Validate the engine's allowlist before storing the choice.
    }
    private static void validateParameters(Engine engine,String type){
        String base=type.replaceAll("\\([^)]*\\)","").replaceAll(" (?:with(?: local)?|without) time zone$", "").trim();
        var parameters=java.util.regex.Pattern.compile("\\((\\d+)(?:\\s*,\\s*(\\d+))?(?: (char))?\\)").matcher(type);
        boolean has=parameters.find();
        if(!has){if(Set.of("char","varchar","varchar2","nvarchar2","decimal").contains(base))throw new IllegalArgumentException("目标类型需要明确长度或精度："+type);return;}
        if(!Set.of("char","varchar","varchar2","nvarchar2","decimal","numeric","number","timestamp","datetime","time").contains(base))throw new IllegalArgumentException("该目标类型不接受长度参数："+type);
        int size=Integer.parseInt(parameters.group(1));String scale=parameters.group(2),semantics=parameters.group(3);
        boolean numeric=Set.of("decimal","numeric","number").contains(base),temporal=Set.of("timestamp","datetime","time").contains(base);
        if(semantics!=null&&(engine!=Engine.ORACLE||!Set.of("char","varchar2").contains(base)))throw new IllegalArgumentException("仅 Oracle CHAR/VARCHAR2 支持 CHAR 长度语义");
        if(scale!=null&&!numeric)throw new IllegalArgumentException("仅数值类型支持小数位数："+type);
        if(temporal){if(size>(engine==Engine.ORACLE?9:6))throw new IllegalArgumentException("时间精度超出目标引擎范围");return;}
        if(size<1)throw new IllegalArgumentException("字段长度或精度必须大于零");
        if(numeric){int max=engine==Engine.ORACLE?38:engine==Engine.MYSQL?65:1000;int digits=scale==null?0:Integer.parseInt(scale);if(size>max||digits>size||(engine==Engine.MYSQL&&digits>30))throw new IllegalArgumentException("目标数值精度超出支持范围："+type);}
        else{int max=engine==Engine.MYSQL?(base.equals("char")?255:16383):engine==Engine.ORACLE?(base.equals("char")||base.equals("nvarchar2")?2000:4000):10485760;if(size>max)throw new IllegalArgumentException("目标字段长度超出支持范围："+type);}
    }
    int jdbcType(){return jdbcType(engine,type);}
    private static int jdbcType(Engine engine,String type){
        String base=type.replaceAll("\\([^)]*\\)","").trim();
        Map<String,Integer> allowed=switch(engine){
            case MYSQL -> Map.ofEntries(Map.entry("smallint",Types.SMALLINT),Map.entry("int",Types.INTEGER),Map.entry("bigint",Types.BIGINT),Map.entry("decimal",Types.DECIMAL),Map.entry("float",Types.REAL),Map.entry("double",Types.DOUBLE),Map.entry("boolean",Types.BOOLEAN),Map.entry("char",Types.CHAR),Map.entry("varchar",Types.VARCHAR),Map.entry("text",Types.VARCHAR),Map.entry("longtext",Types.VARCHAR),Map.entry("blob",Types.VARBINARY),Map.entry("longblob",Types.VARBINARY),Map.entry("date",Types.DATE),Map.entry("datetime",Types.TIMESTAMP),Map.entry("time",Types.TIME),Map.entry("json",Types.VARCHAR));
            case POSTGRES -> Map.ofEntries(Map.entry("smallint",Types.SMALLINT),Map.entry("integer",Types.INTEGER),Map.entry("bigint",Types.BIGINT),Map.entry("numeric",Types.NUMERIC),Map.entry("real",Types.REAL),Map.entry("double precision",Types.DOUBLE),Map.entry("boolean",Types.BOOLEAN),Map.entry("char",Types.CHAR),Map.entry("varchar",Types.VARCHAR),Map.entry("text",Types.VARCHAR),Map.entry("bytea",Types.VARBINARY),Map.entry("date",Types.DATE),Map.entry("timestamp",Types.TIMESTAMP),Map.entry("timestamp without time zone",Types.TIMESTAMP),Map.entry("time",Types.TIME),Map.entry("json",Types.OTHER),Map.entry("jsonb",Types.OTHER),Map.entry("uuid",Types.OTHER),Map.entry("timestamp with time zone",Types.TIMESTAMP_WITH_TIMEZONE),Map.entry("time with time zone",Types.TIME_WITH_TIMEZONE));
            case ORACLE -> Map.ofEntries(Map.entry("number",Types.NUMERIC),Map.entry("binary_float",Types.REAL),Map.entry("binary_double",Types.DOUBLE),Map.entry("char",Types.VARCHAR),Map.entry("varchar2",Types.VARCHAR),Map.entry("nvarchar2",Types.VARCHAR),Map.entry("clob",Types.VARCHAR),Map.entry("blob",Types.VARBINARY),Map.entry("date",Types.TIMESTAMP),Map.entry("timestamp",Types.TIMESTAMP),Map.entry("timestamp with time zone",Types.TIMESTAMP_WITH_TIMEZONE),Map.entry("timestamp with local time zone",Types.TIMESTAMP_WITH_TIMEZONE));
            default -> Map.of();
        };
        Integer jdbc=allowed.get(base);if(jdbc==null)throw new IllegalArgumentException("未支持的手工目标类型："+type);return jdbc;
    }
}
