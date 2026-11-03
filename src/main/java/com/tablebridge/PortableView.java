package com.tablebridge;

import java.util.*;

/** Conservative SELECT view converter. Unsupported syntax fails during planning. */
final class PortableView {
    record Converted(String sql,Set<String> dependencies){}
    private static final Set<String> WORDS=Set.of("select","distinct","all","from","join","inner","left","right","full","outer","cross","on","as","where","and","or","not","is","null","in","between","like","escape","group","by","having","order","asc","desc","union","case","when","then","else","end");
    static Converted convert(Engine from,Engine to,String sourceNamespace,String targetNamespace,String name,String query,Map<String,String> names){
        if(from==Engine.MSSQL||to==Engine.MSSQL)throw new IllegalArgumentException("此视图转换不支持 SQL Server");
        List<SqlNames.Token> rawTokens=SqlNames.tokens(query);List<SqlNames.Token> compact=new ArrayList<>();
        for(int i=0;i<rawTokens.size();i++){
            var token=rawTokens.get(i);if(token.raw().isBlank())continue;
            if(token.raw().matches("[0-9]")){
                StringBuilder number=new StringBuilder(token.raw());
                while(i+1<rawTokens.size()&&rawTokens.get(i+1).raw().matches("[0-9.]"))number.append(rawTokens.get(++i).raw());
                if(!number.toString().matches("[0-9]+(?:\\.[0-9]+)?"))throw new IllegalArgumentException("视图数值语法需进一步转换");
                compact.add(new SqlNames.Token(number.toString(),null,false));
            }else if(i+1<rawTokens.size()&&Set.of(">=","<=","<>","!=").contains(token.raw()+rawTokens.get(i+1).raw())){
                compact.add(new SqlNames.Token(token.raw()+rawTokens.get(++i).raw(),null,false));
            }else compact.add(token);
        }
        if(!compact.isEmpty()&&compact.getLast().raw().equals(";"))compact.removeLast();
        List<SqlNames.Token> tokens=selectBody(compact,sourceNamespace);
        if(tokens.isEmpty()||!tokens.getFirst().raw().equalsIgnoreCase("select"))throw new IllegalArgumentException("自动视图转换需要 SELECT 定义");
        List<String> out=new ArrayList<>();Set<String> dependencies=new LinkedHashSet<>();boolean relation=false;int depth=0;
        for(int i=0;i<tokens.size();i++){
            var t=tokens.get(i);String raw=t.raw(),word=raw.toLowerCase(Locale.ROOT);
            if(raw.startsWith("--")||raw.startsWith("/*")||raw.equals(";")||raw.equals(":"))throw new IllegalArgumentException("视图包含需手工转换的语法");
            if(i>0&&word.equals("select"))throw new IllegalArgumentException("嵌套查询和 UNION 视图需进一步转换");
            if(relation){
                if(t.identifier()==null)throw new IllegalArgumentException("视图关系不是普通表或视图名称");
                String object=t.identifier();
                if(i+1<tokens.size()&&tokens.get(i+1).raw().equals(".")){
                    if(!object.equals(sourceNamespace)||i+2>=tokens.size()||tokens.get(i+2).identifier()==null)throw new IllegalArgumentException("视图引用了来源模式外的对象");
                    object=tokens.get(i+2).identifier();i+=2;
                }
                if(i+1<tokens.size()&&(tokens.get(i+1).raw().equals(".")||tokens.get(i+1).raw().equals("(")))throw new IllegalArgumentException("视图包含三段式引用或表函数");
                if(!names.containsKey(object))throw new IllegalArgumentException("请补选视图依赖："+object);
                dependencies.add(object);out.add(to.qualified(targetNamespace,names.get(object)));
                boolean alias=i+1<tokens.size()&&(tokens.get(i+1).raw().equalsIgnoreCase("as")||tokens.get(i+1).identifier()!=null&&!WORDS.contains(tokens.get(i+1).raw().toLowerCase(Locale.ROOT)));
                if(!alias)out.add(to.quote(object));
                relation=false;continue;
            }
            if(word.equals("from")||word.equals("join")){relation=true;out.add(word.toUpperCase(Locale.ROOT));continue;}
            if(t.literal()){
                if(!raw.startsWith("'")||raw.contains("\\"))throw new IllegalArgumentException("视图字符串需手工转换");
                out.add(raw);continue;
            }
            if(raw.equals("(")){depth++;}if(raw.equals(")")&&--depth<0)throw new IllegalArgumentException("视图括号不匹配");
            if(t.identifier()!=null){
                if(word.equals("full")&&to==Engine.MYSQL)throw new IllegalArgumentException("MySQL 不支持 FULL JOIN，需手工转换");
                if(t.identifier().equals(sourceNamespace)&&i+1<tokens.size()&&tokens.get(i+1).raw().equals("."))throw new IllegalArgumentException("视图字段含来源模式限定，需进一步转换");
                if(i+1<tokens.size()&&tokens.get(i+1).raw().equals("(")&&!word.equals("in"))throw new IllegalArgumentException("视图函数需手工转换："+raw);
                if(word.equals("true")||word.equals("false"))throw new IllegalArgumentException("视图布尔字面量需按字段映射转换");
                out.add(WORDS.contains(word)&&raw.equals(t.identifier())?word.toUpperCase(Locale.ROOT):to.quote(t.identifier()));
            }else{
                if(!raw.matches("(?:[0-9]+(?:\\.[0-9]+)?|>=|<=|<>|!=|[()<>!=+*/%,.\\-])"))throw new IllegalArgumentException("视图包含不支持符号："+raw);
                // Comma-separated FROM relations need a parser; never leave one in the source namespace.
                if(raw.equals(",")&&dependencies.size()>0)throw new IllegalArgumentException("FROM 后的逗号表达式需进一步转换");
                out.add(raw);
            }
        }
        if(relation||depth!=0)throw new IllegalArgumentException("视图定义不完整");
        return new Converted("CREATE VIEW "+to.qualified(targetNamespace,name)+" AS "+String.join(" ",out),Set.copyOf(dependencies));
    }
    private static List<SqlNames.Token> selectBody(List<SqlNames.Token> tokens,String namespace){
        if(tokens.isEmpty()||!tokens.getFirst().raw().equalsIgnoreCase("create"))return tokens;
        int i=1;
        if(i+1<tokens.size()&&tokens.get(i).raw().equalsIgnoreCase("or")&&tokens.get(i+1).raw().equalsIgnoreCase("replace"))i+=2;
        if(i>=tokens.size()||!tokens.get(i++).raw().equalsIgnoreCase("view"))throw new IllegalArgumentException("视图 CREATE 包装需要手工转换");
        if(i>=tokens.size()||tokens.get(i).identifier()==null)throw new IllegalArgumentException("视图名称未加载");
        String first=tokens.get(i++).identifier();
        if(i<tokens.size()&&tokens.get(i).raw().equals(".")){
            if(!first.equals(namespace)||i+1>=tokens.size()||tokens.get(i+1).identifier()==null)throw new IllegalArgumentException("视图 CREATE 模式与来源不一致");i+=2;
        }
        if(i>=tokens.size()||!tokens.get(i++).raw().equalsIgnoreCase("as"))throw new IllegalArgumentException("视图选项或列列表需要进一步转换");
        return tokens.subList(i,tokens.size());
    }
    static List<String> order(Map<String,Set<String>> graph){
        List<String> result=new ArrayList<>();Set<String> active=new HashSet<>(),done=new HashSet<>();
        for(String name:graph.keySet())visit(name,graph,active,done,result);
        return List.copyOf(result);
    }
    private static void visit(String name,Map<String,Set<String>> graph,Set<String> active,Set<String> done,List<String> result){
        if(done.contains(name))return;if(!active.add(name))throw new IllegalArgumentException("视图循环依赖："+name);
        for(String dependency:graph.get(name))if(graph.containsKey(dependency))visit(dependency,graph,active,done,result);
        active.remove(name);done.add(name);result.add(name);
    }
}
