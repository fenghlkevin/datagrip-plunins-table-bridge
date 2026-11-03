package com.tablebridge;

import java.sql.Types;
import java.util.*;

/** Explicit, reviewable table conversion for the MySQL/PostgreSQL/Oracle cross-engine paths. */
final class CrossEngineDdl {
    record Column(String name,String type,int jdbcType,int size,int scale,boolean notNull,boolean identity,String defaultValue,boolean computed){}
    record Mapped(String sql,int jdbcType){}
    record Definition(String sql,List<TransferPlan.Column> columns,List<String> warnings){}
    static boolean supports(Engine from,Engine to){return from==to||(Set.of(Engine.MYSQL,Engine.POSTGRES,Engine.ORACLE).contains(from)&&Set.of(Engine.MYSQL,Engine.POSTGRES,Engine.ORACLE).contains(to));}
    static Mapped type(Column c,Engine from,Engine to){
        String raw=c.type.trim().toLowerCase(Locale.ROOT);boolean unsigned=raw.contains("unsigned");
        int jdbcType=nativeJdbcType(raw,from);
        if(from==Engine.ORACLE&&c.identity&&jdbcType==Types.NUMERIC&&to!=Engine.ORACLE)return new Mapped("bigint",Types.BIGINT);
        if(c.computed)throw new IllegalArgumentException("跨类型暂不转换计算列："+c.name);
        if(c.identity&&(raw.contains("unsigned")&&raw.contains("bigint")))throw new IllegalArgumentException("unsigned bigint 自增范围超出 PostgreSQL identity："+c.name);
        if(from==Engine.ORACLE&&raw.equals("date"))return new Mapped(to==Engine.POSTGRES?"timestamp(0)":"datetime(0)",Types.TIMESTAMP);
        if(to==Engine.ORACLE)return oracleType(c,from,raw,jdbcType);
        if(ZonedValues.timestamp(raw)){
            if(to==Engine.MYSQL)return new Mapped("varchar(64)",Types.VARCHAR);
            return new Mapped("timestamp("+temporalPrecision(c,from)+") with time zone",Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if(to==Engine.POSTGRES){
            if(raw.matches(".*\\b(enum|set|geometry|point|linestring|polygon)\\b.*"))throw new IllegalArgumentException("需手工映射类型："+c.type);
            if(raw.equals("json"))return new Mapped("json",Types.OTHER);
            return switch(jdbcType){
                case Types.TINYINT -> new Mapped("smallint",Types.SMALLINT);
                case Types.SMALLINT -> new Mapped(unsigned?"integer":"smallint",unsigned?Types.INTEGER:Types.SMALLINT);
                case Types.INTEGER -> new Mapped(unsigned?"bigint":"integer",unsigned?Types.BIGINT:Types.INTEGER);
                case Types.BIGINT -> new Mapped(unsigned?"numeric(20,0)":"bigint",unsigned?Types.NUMERIC:Types.BIGINT);
                case Types.NUMERIC,Types.DECIMAL -> new Mapped(from==Engine.ORACLE&&c.size<=0?"numeric":numeric(c,"numeric",1000),Types.NUMERIC);
                case Types.FLOAT,Types.REAL -> new Mapped("real",Types.REAL);
                case Types.DOUBLE -> new Mapped("double precision",Types.DOUBLE);
                case Types.BOOLEAN -> new Mapped("boolean",Types.BOOLEAN);
                case Types.BIT -> {if(c.size>1)throw new IllegalArgumentException("多位 BIT 需手工映射："+c.name);yield new Mapped("boolean",Types.BOOLEAN);}
                case Types.CHAR,Types.NCHAR -> new Mapped(length(c,"char"),Types.CHAR);
                case Types.VARCHAR,Types.NVARCHAR -> new Mapped(c.size>0?length(c,"varchar"):"text",Types.VARCHAR);
                case Types.LONGVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB -> new Mapped("text",Types.VARCHAR);
                case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB -> new Mapped("bytea",Types.VARBINARY);
                case Types.DATE -> new Mapped("date",Types.DATE);
                case Types.TIMESTAMP -> new Mapped("timestamp("+temporalPrecision(c,from)+")",Types.TIMESTAMP);
                // MySQL TIME permits intervals outside a day; do not silently coerce to PostgreSQL time.
                default -> throw new IllegalArgumentException("需手工映射类型："+c.name+" "+c.type);
            };
        }
        if(to!=Engine.MYSQL)throw new IllegalArgumentException("跨类型首版仅支持 MySQL ↔ PostgreSQL");
        if(ZonedValues.time(raw))return new Mapped(to==Engine.ORACLE?"varchar2(64 char)":"varchar(64)",Types.VARCHAR);
        if(raw.equals("uuid"))return new Mapped("char(36)",Types.VARCHAR);
        if(raw.equals("json")||raw.equals("jsonb"))return new Mapped("longtext",Types.VARCHAR);
        if(raw.contains("with time zone")||raw.equals("timestamptz")||raw.equals("timetz"))throw new IllegalArgumentException("带时区字段需明确时区转换策略："+c.name);
        return switch(jdbcType){
            case Types.SMALLINT,Types.TINYINT -> new Mapped("smallint",Types.SMALLINT);
            case Types.INTEGER -> new Mapped("int",Types.INTEGER);
            case Types.BIGINT -> new Mapped("bigint",Types.BIGINT);
            case Types.NUMERIC,Types.DECIMAL -> new Mapped(from==Engine.ORACLE&&c.size<=0?"longtext":numeric(c,"decimal",65),from==Engine.ORACLE&&c.size<=0?Types.VARCHAR:Types.DECIMAL);
            case Types.FLOAT,Types.REAL -> new Mapped("float",Types.FLOAT);
            case Types.DOUBLE -> new Mapped("double",Types.DOUBLE);
            case Types.BOOLEAN -> new Mapped("boolean",Types.BOOLEAN);
            case Types.CHAR,Types.NCHAR -> {if(c.size>255)throw new IllegalArgumentException("MySQL CHAR 最大 255："+c.name);yield new Mapped(length(c,"char"),Types.CHAR);}
            case Types.VARCHAR,Types.NVARCHAR -> {if(raw.equals("text")||c.size<=0)yield new Mapped("longtext",Types.VARCHAR);if(c.size>16383)throw new IllegalArgumentException("varchar 过长，需选择文本类型："+c.name);yield new Mapped(length(c,"varchar"),Types.VARCHAR);}
            case Types.LONGVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB -> new Mapped("longtext",Types.VARCHAR);
            case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB -> new Mapped("longblob",Types.VARBINARY);
            case Types.DATE -> new Mapped("date",Types.DATE);
            case Types.TIME -> new Mapped("time("+temporalPrecision(c,from)+")",Types.TIME);
            case Types.TIMESTAMP -> new Mapped("datetime("+temporalPrecision(c,from)+")",Types.TIMESTAMP);
            default -> throw new IllegalArgumentException("需手工映射类型："+c.name+" "+c.type);
        };
    }
    // DataGrip's introspected DataType may have no JDBC code. Resolve only known
    // native scalar names; unknown domains and arrays must not inherit a lossy base type.
    private static int nativeJdbcType(String raw,Engine from){
        String name=raw.replaceAll("\\([0-9]+\\s+(?:char|byte)\\)", "").replaceAll("\\([0-9]+(?:\\s*,\\s*-?[0-9]+)?\\)", "").trim().replaceAll("\\s+", " ");
        if(from==Engine.MYSQL)name=name.replaceAll(" (unsigned|zerofill)", "");
        if(from==Engine.ORACLE)return switch(name){
            case "number","numeric","decimal","integer","int","smallint" -> Types.NUMERIC;
            case "binary_float" -> Types.REAL;
            case "binary_double","float","double precision" -> Types.DOUBLE;
            case "varchar2","nvarchar2","varchar" -> Types.VARCHAR;
            case "char","nchar" -> Types.CHAR;
            case "clob","nclob","long" -> Types.LONGVARCHAR;
            case "raw","long raw","blob" -> Types.VARBINARY;
            case "date","timestamp" -> Types.TIMESTAMP;
            case "timestamp with time zone","timestamp with local time zone" -> Types.TIMESTAMP_WITH_TIMEZONE;
            default -> Types.OTHER;
        };
        if(from==Engine.POSTGRES)return switch(name){
            case "smallint","int2","smallserial","serial2" -> Types.SMALLINT;
            case "integer","int","int4","serial","serial4" -> Types.INTEGER;
            case "bigint","int8","bigserial","serial8" -> Types.BIGINT;
            case "numeric","decimal" -> Types.NUMERIC;
            case "real","float4" -> Types.REAL;
            case "double precision","float8" -> Types.DOUBLE;
            case "boolean","bool" -> Types.BOOLEAN;
            case "character","char","bpchar" -> Types.CHAR;
            case "character varying","varchar" -> Types.VARCHAR;
            case "text" -> Types.LONGVARCHAR;
            case "bytea" -> Types.VARBINARY;
            case "date" -> Types.DATE;
            case "time","time without time zone" -> Types.TIME;
            case "timestamp","timestamp without time zone" -> Types.TIMESTAMP;
            default -> Types.OTHER;
        };
        return switch(name){
            case "tinyint" -> Types.TINYINT;
            case "smallint" -> Types.SMALLINT;
            case "mediumint","int","integer" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "numeric","decimal","dec","fixed" -> Types.DECIMAL;
            case "float" -> Types.REAL;
            case "double","double precision" -> Types.DOUBLE;
            case "boolean","bool" -> Types.BOOLEAN;
            case "bit" -> Types.BIT;
            case "char" -> Types.CHAR;
            case "varchar" -> Types.VARCHAR;
            case "tinytext","text","mediumtext","longtext" -> Types.LONGVARCHAR;
            case "binary","varbinary","tinyblob","blob","mediumblob","longblob" -> Types.VARBINARY;
            case "date" -> Types.DATE;
            case "datetime","timestamp" -> Types.TIMESTAMP;
            default -> Types.OTHER;
        };
    }
    private static Mapped oracleType(Column c,Engine from,String raw,int type){
        if(ZonedValues.time(raw))return new Mapped("varchar2(64 char)",Types.VARCHAR);
        if(raw.equals("uuid"))return new Mapped("varchar2(36 char)",Types.VARCHAR);
        if(raw.equals("json")||raw.equals("jsonb"))return new Mapped("clob",Types.VARCHAR);
        if(raw.contains("with time zone")||raw.equals("timestamptz")||raw.equals("timetz"))return new Mapped("timestamp(6) with time zone",Types.TIMESTAMP_WITH_TIMEZONE);
        return switch(type){
            case Types.TINYINT,Types.SMALLINT,Types.INTEGER -> new Mapped("number(10,0)",Types.NUMERIC);
            case Types.BIGINT -> new Mapped("number(20,0)",Types.NUMERIC);
            case Types.NUMERIC,Types.DECIMAL -> new Mapped(numeric(c,"number",38),Types.NUMERIC);
            case Types.REAL,Types.FLOAT -> new Mapped("binary_float",Types.REAL);
            case Types.DOUBLE -> new Mapped("binary_double",Types.DOUBLE);
            case Types.BOOLEAN -> new Mapped("number(1,0)",Types.NUMERIC);
            case Types.BIT -> {if(c.size>1)throw new IllegalArgumentException("多位 BIT 需手工映射："+c.name);yield new Mapped("number(1,0)",Types.NUMERIC);}
            case Types.CHAR,Types.NCHAR -> new Mapped(c.size>0&&c.size<=2000?"char("+c.size+" char)":"clob",Types.VARCHAR);
            case Types.VARCHAR,Types.NVARCHAR -> new Mapped(c.size>0&&c.size<=1000?"varchar2("+c.size+" char)":"clob",Types.VARCHAR);
            case Types.LONGVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB -> new Mapped("clob",Types.VARCHAR);
            case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB -> new Mapped("blob",Types.VARBINARY);
            case Types.DATE -> new Mapped("date",Types.DATE);
            case Types.TIMESTAMP -> new Mapped("timestamp("+temporalPrecision(c,from)+")",Types.TIMESTAMP);
            default -> throw new IllegalArgumentException("需手工映射 Oracle 类型："+c.name+" "+c.type);
        };
    }
    private static String length(Column c,String type){if(c.size<=0)throw new IllegalArgumentException("缺少字段长度："+c.name);return type+"("+c.size+")";}
    private static String numeric(Column c,String type,int max){if(c.size<=0||c.size>max||c.scale<0||c.scale>c.size||(type.equals("decimal")&&c.scale>30))throw new IllegalArgumentException("数值精度不能安全映射："+c.name);return type+"("+c.size+","+c.scale+")";}
    private static int temporalPrecision(Column c,Engine from){
        if(from==Engine.ORACLE&&c.type.trim().equalsIgnoreCase("date"))return 0;
        var matcher=java.util.regex.Pattern.compile("(?i)^(?:timestamp|datetime|time)\\s*\\((\\d+)\\)").matcher(c.type.trim());
        int precision=matcher.find()?Integer.parseInt(matcher.group(1)):from==Engine.MYSQL?0:6;
        if(precision>6)throw new IllegalArgumentException("时间精度超出目标支持范围："+c.name);
        return precision;
    }
    static String defaultSql(Column c,Engine to){return defaultSql(c,to==Engine.MYSQL?Engine.POSTGRES:Engine.MYSQL,to);}
    static String defaultSql(Column c,Engine from,Engine to){
        if(c.identity||c.defaultValue==null||c.defaultValue.isBlank())return "";
        String v=c.defaultValue.trim();
        // Unwrap only a complete surrounding expression, never arbitrary SQL fragments.
        while(v.startsWith("(")&&v.endsWith(")")&&wholeParenthesized(v))v=v.substring(1,v.length()-1).trim();
        if(from==Engine.ORACLE&&(v.equalsIgnoreCase("sysdate")||v.equalsIgnoreCase("systimestamp"))){
            if(type(c,from,to).jdbcType!=Types.TIMESTAMP)throw new IllegalArgumentException("Oracle 时间默认值与目标类型不匹配："+c.name);
            return " DEFAULT CURRENT_TIMESTAMP("+temporalPrecision(c,from)+")";
        }
        var current=java.util.regex.Pattern.compile("(?i)^(?:(?:current_timestamp|localtimestamp)(?:\\(\\s*([0-6]?)\\s*\\))?|(?:now|transaction_timestamp)\\(\\s*\\))$").matcher(v);
        if(current.matches()){
            Mapped mapped=type(c,from,to);
            if(mapped.jdbcType!=Types.TIMESTAMP)throw new IllegalArgumentException("当前时间默认值与字段类型不匹配："+c.name);
            int fieldPrecision=temporalPrecision(c,from);
            int expressionPrecision=current.group(1)==null||current.group(1).isBlank()?(from==Engine.POSTGRES?6:0):Integer.parseInt(current.group(1));
            String expression="CURRENT_TIMESTAMP("+expressionPrecision+")";
            // MySQL requires the bare CURRENT_TIMESTAMP precision to equal the column.
            // An expression default preserves a deliberately different source precision.
            if(to==Engine.MYSQL&&expressionPrecision!=fieldPrecision)return " DEFAULT (CAST("+expression+" AS DATETIME("+fieldPrecision+")))";
            return " DEFAULT "+(to==Engine.ORACLE?"LOCALTIMESTAMP("+expressionPrecision+")":expression);
        }
        if(v.matches("(?is)'(?:[^']|'')*'::(?:date|timestamp(?:\\([0-6]\\))?(?: without time zone)?|time(?:\\([0-6]\\))?(?: without time zone)?)"))v=v.substring(0,v.lastIndexOf("::"));
        if(v.matches("(?is)'(?:[^']|'')*'::(?:text|character varying|bpchar|uuid)"))v=v.substring(0,v.lastIndexOf("::"));
        if(to==Engine.ORACLE&&(v.equalsIgnoreCase("true")||v.equalsIgnoreCase("false")))return " DEFAULT "+(v.equalsIgnoreCase("true")?"1":"0");
        if(v.equalsIgnoreCase("null"))return " DEFAULT NULL";
        if(v.matches("[-+]?\\d+(\\.\\d+)?")||v.equalsIgnoreCase("true")||v.equalsIgnoreCase("false"))return " DEFAULT "+v;
        if(v.matches("'([^'\\\\]|'')*'")){
            if(to==Engine.ORACLE){
                Mapped mapped=type(c,from,to);
                if(mapped.jdbcType==Types.DATE||mapped.jdbcType==Types.TIMESTAMP){
                    String literal=v.substring(1,v.length()-1);
                    if(literal.matches("\\d{4}-\\d{2}-\\d{2}"))return " DEFAULT DATE "+v;
                    if(literal.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?"))return " DEFAULT TIMESTAMP '"+literal.replace('T',' ')+"'";
                    throw new IllegalArgumentException("Oracle 时间默认值需明确格式："+c.name);
                }
            }
            return " DEFAULT "+v;
        }
        throw new IllegalArgumentException("默认值表达式需手工转换："+c.name+" = "+v);
    }
    private static boolean wholeParenthesized(String value){
        int depth=0;boolean quoted=false;
        for(int i=0;i<value.length();i++){
            char ch=value.charAt(i);
            if(ch=='\''){if(quoted&&i+1<value.length()&&value.charAt(i+1)=='\''){i++;continue;}quoted=!quoted;}
            if(!quoted){if(ch=='(')depth++;else if(ch==')'&&--depth==0)return i==value.length()-1;}
        }
        return false;
    }
    static Definition build(Engine from,Engine to,String namespace,String table,List<Column> columns,List<String> primary){
        return build(from,to,namespace,table,columns,primary,Map.of());
    }
    static Definition build(Engine from,Engine to,String namespace,String table,List<Column> columns,List<String> primary,Map<String,FieldMapping> overrides){
        return build(from,to,namespace,table,columns,primary,overrides,Map.of());
    }
    static Definition build(Engine from,Engine to,String namespace,String table,List<Column> columns,List<String> primary,Map<String,FieldMapping> overrides,Map<String,String> comments){
        if(from==to||!supports(from,to))throw new IllegalArgumentException("不是受支持的跨类型复制方向");
        List<String> definitions=new ArrayList<>();List<TransferPlan.Column> mapped=new ArrayList<>();List<String> notes=new ArrayList<>();
        Set<String> known=new HashSet<>();columns.forEach(c->known.add(c.name));if(!known.containsAll(overrides.keySet()))throw new IllegalArgumentException("字段映射引用了来源中不存在的字段");
        for(Column c:columns){FieldMapping override=overrides.get(c.name);
            if(override!=null&&override.engine()!=to)throw new IllegalArgumentException("字段映射目标类型与当前目标数据库不一致："+c.name);
            if(c.computed&&(override==null||!override.materialize()))throw new IllegalArgumentException("计算列需明确选择物化复制："+c.name);
            Mapped type=override==null?type(c,from,to):new Mapped(override.type(),override.jdbcType());String identity="";
            String defaultValue=override!=null&&override.omitDefault()?"":defaultSql(c,from,to);
            if(c.identity){if(!Set.of(Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC).contains(type.jdbcType))throw new IllegalArgumentException("自增列必须映射为整数："+c.name);identity=to==Engine.POSTGRES||to==Engine.ORACLE?" GENERATED BY DEFAULT AS IDENTITY":" AUTO_INCREMENT";}
            String comment=comments.get(c.name);
            String annotation=to==Engine.MYSQL&&comment!=null&&!comment.isEmpty()?" COMMENT "+CrossStructure.literalComment(comment):"";
            definitions.add("  "+to.quote(c.name)+" "+type.sql+identity+(to==Engine.ORACLE?defaultValue+(c.notNull?" NOT NULL":""):(c.notNull?" NOT NULL":"")+defaultValue)+annotation);mapped.add(new TransferPlan.Column(c.name,c.identity,type.jdbcType));
            if(c.defaultValue!=null&&!c.defaultValue.isBlank()&&!c.identity)notes.add(c.name+UiText.text(" 默认值：")+c.defaultValue+" → "+(defaultValue.isEmpty()?UiText.text("手工选择不复制"):defaultValue.trim()));
            notes.add(c.name+UiText.text("：")+c.type+" → "+type.sql+(override==null?"":UiText.text("（手工映射")+(override.materialize()?UiText.text("，计算值物化"):"")+UiText.text("）"))+(c.identity?UiText.text("（独立自增列）"):""));
        }
        if(columns.isEmpty())throw new IllegalArgumentException("表字段元数据未加载");
        if(!primary.isEmpty())definitions.add("  PRIMARY KEY ("+String.join(", ",primary.stream().map(to::quote).toList())+")");
        if(to==Engine.MYSQL)for(Column c:columns)if(c.identity&&(primary.isEmpty()||!primary.getFirst().equals(c.name)))definitions.add("  INDEX ("+to.quote(c.name)+")");
        String sql="CREATE TABLE "+to.qualified(namespace,table)+" (\n"+String.join(",\n",definitions)+"\n)"+(to==Engine.MYSQL?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4":"");
        return new Definition(sql,List.copyOf(mapped),List.copyOf(notes));
    }
}
