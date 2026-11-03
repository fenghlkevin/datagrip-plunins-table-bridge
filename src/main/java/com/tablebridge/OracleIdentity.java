package com.tablebridge;
import java.util.*;
import java.util.regex.*;
/** Load explicit values under BY DEFAULT, then restore generation and advance above imported records. */
final class OracleIdentity {
    record Prepared(String create,List<String> after){}
    static Prepared prepare(String create,List<String> identityColumns,String table){
        if(identityColumns.isEmpty())return new Prepared(create,List.of());
        List<SqlNames.Token> ts=SqlNames.tokens(create);Map<String,String> modes=new LinkedHashMap<>();StringBuilder out=new StringBuilder();int depth=0,start=0;
        for(int i=0;i<ts.size();i++){
            String raw=ts.get(i).raw();if(raw.equals("(")){depth++;if(depth==1){for(int j=start;j<=i;j++)out.append(ts.get(j).raw());start=i+1;}}
            else if(raw.equals(")")){if(depth==1){append(ts,start,i,identityColumns,modes,out);out.append(raw);start=i+1;}depth--;}
            else if(raw.equals(",")&&depth==1){append(ts,start,i,identityColumns,modes,out);out.append(raw);start=i+1;}
        }
        for(int j=start;j<ts.size();j++)out.append(ts.get(j).raw());
        if(!modes.keySet().containsAll(identityColumns))throw new IllegalArgumentException("Oracle identity 定义不完整；请在完整结构中声明 GENERATED … AS IDENTITY");
        List<String> after=new ArrayList<>();modes.forEach((name,mode)->after.add("ALTER TABLE "+table+" MODIFY ("+Engine.ORACLE.quote(name)+" GENERATED "+mode+" AS IDENTITY (START WITH LIMIT VALUE))"));return new Prepared(out.toString(),List.copyOf(after));
    }
    private static void append(List<SqlNames.Token> tokens,int start,int end,List<String> columns,Map<String,String> modes,StringBuilder out){
        StringBuilder block=new StringBuilder();String name=null;for(int j=start;j<end;j++){var t=tokens.get(j);if(name==null&&t.identifier()!=null)name=t.identifier();block.append(t.raw());}
        String text=block.toString();if(name!=null&&columns.contains(name)){Matcher m=Pattern.compile("(?i)GENERATED\\s+(ALWAYS|BY\\s+DEFAULT(?:\\s+ON\\s+NULL)?)\\s+AS\\s+IDENTITY").matcher(text);if(!m.find())throw new IllegalArgumentException("Oracle identity 缺失生成策略："+name);String mode=m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+"," ");modes.put(name,mode);if(mode.equals("ALWAYS"))text=text.substring(0,m.start(1))+"BY DEFAULT"+text.substring(m.end(1));}out.append(text);
    }
}
