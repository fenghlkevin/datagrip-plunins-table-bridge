package com.tablebridge;

import java.util.*;
import java.util.regex.Pattern;

/** Token-aware namespace rebasing. Never rewrites comments or procedure string bodies. */
public final class SqlNames {
    record Token(String raw, String identifier, boolean literal) {}
    static List<Token> tokens(String sql) {
        List<Token> out=new ArrayList<>();
        for(int i=0;i<sql.length();) {
            int start=i;char c=sql.charAt(i);
            if(Character.isWhitespace(c)){while(i<sql.length()&&Character.isWhitespace(sql.charAt(i)))i++;out.add(new Token(sql.substring(start,i),null,false));continue;}
            if(sql.startsWith("--",i)){int end=sql.indexOf('\n',i);i=end<0?sql.length():end;out.add(new Token(sql.substring(start,i),null,false));continue;}
            if(sql.startsWith("/*",i)){int depth=1;i+=2;while(i<sql.length()&&depth>0){if(sql.startsWith("/*",i)){depth++;i+=2;}else if(sql.startsWith("*/",i)){depth--;i+=2;}else i++;}if(depth!=0)throw new IllegalArgumentException("未闭合 SQL 注释");out.add(new Token(sql.substring(start,i),null,false));continue;}
            if(c=='\''||c=='"'||c=='`'||c=='['){char end=c=='['?']':c;StringBuilder value=new StringBuilder();i++;boolean done=false;while(i<sql.length()){char ch=sql.charAt(i++);if(ch==end){if(i<sql.length()&&sql.charAt(i)==end){value.append(ch);i++;}else{done=true;break;}}else if(ch=='\\'&&c=='\''&&i<sql.length()){value.append(ch).append(sql.charAt(i++));}else value.append(ch);}if(!done)throw new IllegalArgumentException("未闭合 SQL 引号");out.add(new Token(sql.substring(start,i),c=='\''?null:value.toString(),c=='\''));continue;}
            if(c=='$'){var matcher=Pattern.compile("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$").matcher(sql.substring(i));if(matcher.lookingAt()){String tag=matcher.group();int end=sql.indexOf(tag,i+tag.length());if(end<0)throw new IllegalArgumentException("未闭合函数体");i=end+tag.length();out.add(new Token(sql.substring(start,i),null,true));continue;}}
            if(Character.isLetter(c)||c=='_'||c=='#'){i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||"_$#".indexOf(sql.charAt(i))>=0))i++;String raw=sql.substring(start,i);out.add(new Token(raw,raw,false));continue;}
            i++;out.add(new Token(sql.substring(start,i),null,false));
        }return out;
    }
    private static int next(List<Token> t,int i){while(i<t.size()&&(t.get(i).raw.isBlank()||t.get(i).raw.startsWith("--")||t.get(i).raw.startsWith("/*")))i++;return i;}
    public static String rebase(String sql,Engine engine,String from,String to,Map<String,String> names) {
        List<Token> ts=tokens(sql);StringBuilder out=new StringBuilder();int structureName=structureNameIndex(ts);
        for(int j=0;j<ts.size();j++) if(from.equals(ts.get(j).identifier)) {
            int prev=j-1;while(prev>=0&&ts.get(prev).raw.isBlank())prev--;
            if(prev>=0&&ts.get(prev).raw.equals("."))throw new IllegalArgumentException("检测到跨数据库三段式引用；请使用直接连接目标数据库的连接并手动检查定义");
        }
        for(int i=0;i<ts.size();i++) {
            Token t=ts.get(i);int dot=next(ts,i+1),obj=next(ts,dot+1);
            if(from.equals(t.identifier)&&dot<ts.size()&&ts.get(dot).raw.equals(".")&&obj<ts.size()&&ts.get(obj).identifier!=null){out.append(engine.quote(to));for(int k=i+1;k<obj;k++)out.append(ts.get(k).raw);String old=ts.get(obj).identifier;out.append(engine.quote(obj==structureName?old:names.getOrDefault(old,old)));i=obj;continue;}
            if(engine==Engine.POSTGRES&&t.literal&&t.raw.startsWith("'")){
                int cast=next(ts,i+1);
                if(cast+2<ts.size()&&ts.get(cast).raw.equals(":")&&ts.get(cast+1).raw.equals(":")&&"regclass".equalsIgnoreCase(ts.get(cast+2).identifier)){
                    var ref=PostgresSequences.reference("nextval("+t.raw+"::regclass)",from);
                    if(ref!=null&&ref.schema().equals(from)){out.append("'").append(engine.qualified(to,names.getOrDefault(ref.name(),ref.name())).replace("'","''")).append("'");continue;}
                }
            }
            if(t.literal && (!from.equals(to) || names.entrySet().stream().anyMatch(e -> !e.getKey().equals(e.getValue()))) && (t.raw.contains(from+".")||t.raw.contains(engine.quote(from)+"."))) {
                // PostgreSQL serial defaults carry a regclass literal; do not edit arbitrary function bodies.
                int cast=next(ts,i+1);
                if(t.raw.startsWith("'")&&cast+2<ts.size()&&ts.get(cast).raw.equals(":")&&ts.get(cast+1).raw.equals(":")&&"regclass".equalsIgnoreCase(ts.get(cast+2).identifier)) {
                    String raw=t.raw.substring(1,t.raw.length()-1).replace("''", "'");String suffix;
                    if(raw.startsWith(from+"."))suffix=raw.substring(from.length()+1);else if(raw.startsWith(engine.quote(from)+"."))suffix=raw.substring(engine.quote(from).length()+1);else throw new IllegalArgumentException("序列引用无法自动重定向");
                    List<Token> sequenceTokens=tokens(suffix);
                    if(sequenceTokens.size()!=1 || sequenceTokens.getFirst().identifier==null)throw new IllegalArgumentException("序列名称无法安全重定向");
                    String sequenceName=sequenceTokens.getFirst().identifier;
                    suffix=engine.quote(names.getOrDefault(sequenceName,sequenceName));
                    out.append("'").append((engine.quote(to)+"."+suffix).replace("'","''")).append("'");continue;
                }
                throw new IllegalArgumentException("对象定义中的字符串或函数体引用来源模式 "+from+"；此版本无法安全自动改写，请先手动迁移该对象。");
            }
            out.append(t.raw);
        }return out.toString();
    }
    private static int structureNameIndex(List<Token> ts){
        List<Integer> sig=new ArrayList<>();for(int i=0;i<ts.size();i++)if(!ts.get(i).raw.isBlank()&&!ts.get(i).raw.startsWith("--")&&!ts.get(i).raw.startsWith("/*"))sig.add(i);
        if(sig.size()<4||!ts.get(sig.get(0)).raw.equalsIgnoreCase("create"))return -1;
        int kind=Set.of("unique","fulltext","spatial","bitmap").contains(ts.get(sig.get(1)).raw.toLowerCase(Locale.ROOT))?2:1;
        if(kind+2>=sig.size()||!Set.of("index","trigger").contains(ts.get(sig.get(kind)).raw.toLowerCase(Locale.ROOT)))return -1;
        return ts.get(sig.get(kind+2)).raw.equals(".")&&kind+3<sig.size()?sig.get(kind+3):sig.get(kind+1);
    }
    static String renameStructureHeader(String sql,Engine engine,Map<String,String> names){
        List<Token> ts=tokens(sql);int name=structureNameIndex(ts);if(name<0)return sql;
        String old=ts.get(name).identifier;if(old==null||!names.containsKey(old))return sql;
        StringBuilder result=new StringBuilder();for(int i=0;i<ts.size();i++)result.append(i==name?engine.quote(names.get(old)):ts.get(i).raw);return result.toString();
    }
    public static void requireCreateOnly(String sql) {
        var ts=tokens(sql);String first=ts.stream().map(Token::identifier).filter(Objects::nonNull).findFirst().orElse("").toUpperCase(Locale.ROOT);
        if(!Set.of("CREATE","ALTER","COMMENT").contains(first))throw new IllegalArgumentException("不支持的生成语句："+first);
        if(Pattern.compile("(?is)^\\s*CREATE\\s+OR\\s+(REPLACE|ALTER)\\b").matcher(sql).find())throw new IllegalArgumentException("首版不允许替换已存在对象");
    }
}
