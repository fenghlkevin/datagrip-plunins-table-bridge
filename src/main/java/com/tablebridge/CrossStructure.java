package com.tablebridge;

import java.util.*;

/** Target dialect structure statements, executed after all tables and their rows exist. */
final class CrossStructure {
    static boolean isForeignKey(String sql){
        List<SqlNames.Token> tokens=SqlNames.tokens(sql).stream().filter(t->!t.raw().isBlank()&&!t.raw().startsWith("--")&&!t.raw().startsWith("/*")).toList();
        for(int i=0;i+1<tokens.size();i++)if(tokens.get(i).raw().equalsIgnoreCase("foreign")&&tokens.get(i+1).raw().equalsIgnoreCase("key"))return true;
        return false;
    }
    static boolean isTrigger(String sql){
        var ts=SqlNames.tokens(sql).stream().filter(t->!t.raw().isBlank()&&!t.raw().startsWith("--")&&!t.raw().startsWith("/*")).toList();
        if(ts.size()<2)return false;String first=ts.get(0).raw().toLowerCase(Locale.ROOT);
        if(!Set.of("create","alter").contains(first))return false;
        if(ts.get(1).raw().equalsIgnoreCase("trigger"))return true;
        if(first.equals("alter")&&ts.get(1).raw().equalsIgnoreCase("table"))for(int i=2;i+1<ts.size();i++)if(Set.of("enable","disable").contains(ts.get(i).raw().toLowerCase(Locale.ROOT))&&ts.get(i+1).raw().equalsIgnoreCase("trigger"))return true;
        return false;
    }
    static int phase(String sql){return isTrigger(sql)?2:isForeignKey(sql)?1:0;}
    record Index(String name,List<String> columns,List<Boolean> descending,boolean unique){}
    record ForeignKey(String name,List<String> columns,String table,List<String> referenced,String deleteRule,String updateRule){}
    static String index(Engine engine,String namespace,String table,Index index){
        if(index.columns.isEmpty()||index.columns.size()!=index.descending.size())throw new IllegalArgumentException("索引字段未完整加载："+index.name);
        List<String> fields=new ArrayList<>();for(int i=0;i<index.columns.size();i++)fields.add(engine.quote(index.columns.get(i))+(index.descending.get(i)?" DESC":""));
        // PostgreSQL chooses the table schema and forbids a qualified CREATE INDEX name.
        String name=engine==Engine.ORACLE?engine.qualified(namespace,index.name):engine.quote(index.name);
        return "CREATE "+(index.unique?"UNIQUE ":"")+"INDEX "+name+" ON "+engine.qualified(namespace,table)+" ("+String.join(", ",fields)+")";
    }
    static String portableIndex(Engine engine,String namespace,String table,Index index,String condition,Set<String> columns){
        if(index.columns.isEmpty()||index.columns.size()!=index.descending.size())throw new IllegalArgumentException("索引字段未完整加载："+index.name);
        if(condition!=null&&!condition.isBlank()&&engine!=Engine.POSTGRES)throw new IllegalArgumentException("目标不支持条件索引；请在完整结构中选择等价方案："+index.name);
        List<String> fields=new ArrayList<>();
        for(int i=0;i<index.columns.size();i++){
            String field=index.columns.get(i);boolean plain=columns.contains(field);
            String rendered=plain?engine.quote(field):"("+indexExpression(engine,field,columns)+")";
            fields.add(rendered+(index.descending.get(i)?" DESC":""));
        }
        String name=engine==Engine.ORACLE?engine.qualified(namespace,index.name):engine.quote(index.name);
        String sql="CREATE "+(index.unique?"UNIQUE ":"")+"INDEX "+name+" ON "+engine.qualified(namespace,table)+" ("+String.join(", ",fields)+")";
        if(condition!=null&&!condition.isBlank()){String checked=check(engine,namespace,table,"preview",condition,columns);sql+=" WHERE "+checked.substring(checked.indexOf(" CHECK (")+8,checked.length()-1);}
        return sql;
    }
    private static String indexExpression(Engine engine,String expression,Set<String> columns){
        var ts=SqlNames.tokens(expression);StringBuilder out=new StringBuilder();int depth=0;boolean seenColumn=false;
        for(int i=0;i<ts.size();i++){
            var token=ts.get(i);String raw=token.raw();
            if(token.literal()||raw.startsWith("--")||raw.startsWith("/*"))throw new IllegalArgumentException("索引表达式需在完整结构中审核："+expression);
            if(token.identifier()!=null){
                if(columns.contains(token.identifier())){out.append(engine.quote(token.identifier()));seenColumn=true;}
                else{int n=i+1;while(n<ts.size()&&ts.get(n).raw().isBlank())n++;if(!Set.of("abs","lower","upper").contains(raw.toLowerCase(Locale.ROOT))||n>=ts.size()||!ts.get(n).raw().equals("("))throw new IllegalArgumentException("索引表达式需手工转换："+expression);out.append(raw);}
            }else{if(raw.equals("("))depth++;if(raw.equals(")")&&--depth<0)throw new IllegalArgumentException("索引表达式括号不匹配");if(!raw.isBlank()&&!raw.matches("[0-9() +*/%,.\\-]"))throw new IllegalArgumentException("索引表达式需手工转换："+expression);out.append(raw);}
        }
        if(depth!=0||!seenColumn)throw new IllegalArgumentException("索引表达式字段/括号未完整加载");return out.toString();
    }
    static String foreignKey(Engine engine,String namespace,String table,ForeignKey key){
        if(key.columns.isEmpty()||key.columns.size()!=key.referenced.size())throw new IllegalArgumentException("外键字段未完整加载："+key.name);
        String delete=rule(engine,key.deleteRule,false),update=rule(engine,key.updateRule,true);
        return "ALTER TABLE "+engine.qualified(namespace,table)+" ADD CONSTRAINT "+engine.quote(key.name)+" FOREIGN KEY ("+fields(engine,key.columns)+") REFERENCES "+engine.qualified(namespace,key.table)+" ("+fields(engine,key.referenced)+")"+delete+update;
    }
    private static String rule(Engine engine,String rule,boolean update){
        if(rule==null||rule.equals("NO_ACTION")||rule.equals("RESTRICT"))return "";
        if(!Set.of("CASCADE","SET_NULL","SET_DEFAULT").contains(rule))throw new IllegalArgumentException("不支持外键规则："+rule);
        if(engine==Engine.ORACLE&&(update||rule.equals("SET_DEFAULT")))throw new IllegalArgumentException("Oracle 不支持此外键规则："+(update?"ON UPDATE ":"ON DELETE ")+rule);
        if(engine==Engine.MYSQL&&rule.equals("SET_DEFAULT"))throw new IllegalArgumentException("MySQL InnoDB 不支持外键 SET DEFAULT");
        return (update?" ON UPDATE ":" ON DELETE ")+rule.replace('_',' ');
    }
    private static String fields(Engine engine,List<String> fields){return String.join(", ",fields.stream().map(engine::quote).toList());}
    static String check(Engine engine,String namespace,String table,String name,String predicate,Set<String> columns){
        if(predicate==null||predicate.isBlank())throw new IllegalArgumentException("检查约束表达式未加载："+name);
        StringBuilder expression=new StringBuilder();int depth=0;
        Set<String> words=Set.of("and","or","not","is","null","in","between","like","escape");
        for(var token:SqlNames.tokens(predicate)){
            String raw=token.raw();
            if(token.literal()){
                if(!raw.startsWith("'")||raw.contains("\\"))throw new IllegalArgumentException("检查约束字符串需手工转换："+name);
                expression.append(raw);continue;
            }
            if(token.identifier()!=null){
                if(columns.contains(token.identifier()))expression.append(engine.quote(token.identifier()));
                else if(words.contains(raw.toLowerCase(Locale.ROOT))&&raw.equals(token.identifier()))expression.append(raw);
                else throw new IllegalArgumentException("检查约束包含需手工转换的表达式："+name+" / "+raw);
                continue;
            }
            if(raw.equals("("))depth++;
            if(raw.equals(")")&&--depth<0)throw new IllegalArgumentException("检查约束括号不匹配："+name);
            if(!raw.isBlank()&&!raw.matches("[0-9()<>!=+*/%,.\\-]"))throw new IllegalArgumentException("检查约束包含不支持的符号："+name);
            expression.append(raw);
        }
        if(depth!=0)throw new IllegalArgumentException("检查约束括号不匹配："+name);
        return "ALTER TABLE "+engine.qualified(namespace,table)+" ADD CONSTRAINT "+engine.quote(name)+" CHECK ("+expression+")";
    }
    static String literalComment(String comment){
        if(comment.codePoints().anyMatch(cp->cp>0xffff))throw new IllegalArgumentException("MySQL 元数据注释不支持补充 Unicode 字符；请修改注释后复制");
        if(comment.contains("\\"))throw new IllegalArgumentException("MySQL 注释包含反斜线，需明确会话转义方式");
        return "'"+comment.replace("'","''")+"'";
    }
    static String comment(Engine engine,String namespace,String table,String column,String comment){
        String literal="'"+comment.replace("'","''")+"'";
        if(engine==Engine.MYSQL){
            if(column!=null)throw new IllegalArgumentException("MySQL 字段注释需随字段定义生成");
            // NO_BACKSLASH_ESCAPES varies by session. Never depend on it for literal text.
            if(comment.contains("\\"))throw new IllegalArgumentException("MySQL 注释包含反斜线，需明确会话转义方式");
            return "ALTER TABLE "+engine.qualified(namespace,table)+" COMMENT = "+literalComment(comment);
        }
        return "COMMENT ON "+(column==null?"TABLE ":"COLUMN ")+engine.qualified(namespace,table)+(column==null?"":"."+engine.quote(column))+" IS "+literal;
    }
}
