package com.tablebridge;

import java.util.*;

/** Pure scalar functions: rebuild signatures and a single RETURN expression for the target. */
final class PortableRoutine {
    record Argument(String name,CrossEngineDdl.Column type){}
    private static final Set<String> FUNCTIONS=Set.of("abs","lower","upper","length","coalesce","nullif");
    private static final Set<String> WORDS=Set.of("case","when","then","else","end","null","and","or","not","is");
    static String create(Engine from,Engine to,String namespace,String name,List<Argument> arguments,CrossEngineDdl.Column result,String definition){
        if(from==to||from==Engine.MSSQL||to==Engine.MSSQL)throw new IllegalArgumentException("不是受支持的函数转换方向");
        List<String> params=new ArrayList<>();Set<String> names=new HashSet<>();
        for(var argument:arguments){if(!names.add(argument.name))throw new IllegalArgumentException("函数参数重复："+argument.name);String mapped=CrossEngineDdl.type(argument.type,from,to).sql();
            if(to==Engine.ORACLE&&mapped.contains("("))throw new IllegalArgumentException("Oracle 函数参数不接受长度/精度约束，需手工审核参数声明："+argument.name);
            params.add(to.quote(argument.name)+" "+mapped);}
        String expression=expression(body(from,definition),to,names);
        String header="CREATE FUNCTION "+to.qualified(namespace,name)+" ("+String.join(", ",params)+")";
        String type=CrossEngineDdl.type(result,from,to).sql();
        if(to==Engine.ORACLE&&type.contains("("))throw new IllegalArgumentException("Oracle 返回值声明需手工审核无约束类型与结果精度");
        return switch(to){
            case POSTGRES -> header+" RETURNS "+type+" LANGUAGE sql AS $tablebridge$ SELECT "+expression+" $tablebridge$";
            case MYSQL -> header+" RETURNS "+type+" DETERMINISTIC NO SQL RETURN "+expression;
            case ORACLE -> header+" RETURN "+type+" IS BEGIN RETURN "+expression+"; END;";
            default -> throw new IllegalArgumentException("SQL Server 未纳入转换");
        };
    }
    private static String body(Engine source,String definition){
        var tokens=SqlNames.tokens(definition).stream().filter(t->!t.raw().isBlank()).toList();
        if(source==Engine.POSTGRES){
            boolean language=false;String body=null;
            for(int i=0;i+1<tokens.size();i++){
                if(tokens.get(i).raw().equalsIgnoreCase("language")&&Set.of("sql","plpgsql").contains(tokens.get(i+1).raw().toLowerCase(Locale.ROOT)))language=true;
                if(tokens.get(i).raw().equalsIgnoreCase("as")&&tokens.get(i+1).literal()){
                    String raw=tokens.get(i+1).raw();
                    if(raw.startsWith("$")){int end=raw.indexOf('$',1);String tag=raw.substring(0,end+1);body=raw.substring(tag.length(),raw.length()-tag.length());}
                    else if(raw.startsWith("'"))body=raw.substring(1,raw.length()-1).replace("''","'");
                }
            }
            if(!language||body==null)throw new IllegalArgumentException("自动函数转换需要 SQL/PLpgSQL 单表达式函数源码");
            return body;
        }
        int begin=-1,returned=-1;
        for(int i=0;i<tokens.size();i++){if(tokens.get(i).raw().equalsIgnoreCase("begin"))begin=i;if(tokens.get(i).raw().equalsIgnoreCase("return"))returned=i;}
        if(returned<0||begin>=0&&returned!=begin+1)throw new IllegalArgumentException("函数含语句或没有单一 RETURN，需手工转换");
        StringBuilder body=new StringBuilder();for(int i=returned;i<tokens.size();i++)body.append(tokens.get(i).raw()).append(' ');
        return body.toString();
    }
    private static String expression(String body,Engine target,Set<String> arguments){
        List<SqlNames.Token> tokens=new ArrayList<>(SqlNames.tokens(body));
        tokens.removeIf(t->t.raw().isBlank());
        if(!tokens.isEmpty()&&tokens.getFirst().raw().equalsIgnoreCase("begin"))tokens.removeFirst();
        if(tokens.isEmpty()||!Set.of("return","select").contains(tokens.removeFirst().raw().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("函数体必须是单个 RETURN/SELECT 表达式");
        if(!tokens.isEmpty()&&tokens.getLast().raw().equals(";"))tokens.removeLast();
        if(!tokens.isEmpty()&&tokens.getLast().raw().equalsIgnoreCase("end")){tokens.removeLast();if(!tokens.isEmpty()&&tokens.getLast().raw().equals(";"))tokens.removeLast();}
        if(tokens.isEmpty())throw new IllegalArgumentException("函数返回表达式为空");
        StringBuilder out=new StringBuilder();int depth=0;
        for(int i=0;i<tokens.size();i++){
            var t=tokens.get(i);String raw=t.raw(),word=raw.toLowerCase(Locale.ROOT);
            if(t.literal()){
                if(!raw.startsWith("'")||raw.contains("\\")||raw.contains("$tablebridge$"))throw new IllegalArgumentException("函数字符串需手工转换");out.append(raw);continue;
            }
            if(t.identifier()!=null){
                if(arguments.contains(t.identifier()))out.append(target.quote(t.identifier()));
                else if(WORDS.contains(word)&&raw.equals(t.identifier()))out.append(' ').append(word.toUpperCase(Locale.ROOT)).append(' ');
                else if(FUNCTIONS.contains(word)&&i+1<tokens.size()&&tokens.get(i+1).raw().equals("("))out.append(word.toUpperCase(Locale.ROOT));
                else throw new IllegalArgumentException("函数表达式引用未知参数或方言函数："+raw);
            }else{
                if(raw.equals("("))depth++;if(raw.equals(")")&&--depth<0)throw new IllegalArgumentException("函数表达式括号不匹配");
                if(!raw.matches("[0-9()<>!=+*/%,.\\-]"))throw new IllegalArgumentException("函数包含需手工转换的语句或符号");out.append(raw);
            }
        }
        if(depth!=0)throw new IllegalArgumentException("函数表达式括号不匹配");
        return out.toString();
    }
}
