package com.tablebridge;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;

final class PostgresSequences {
    record Reference(String schema,String name){}
    static Reference reference(String defaultSql,String currentSchema){
        if(defaultSql==null)return null;
        Matcher m=Pattern.compile("(?is)^\\s*nextval\\s*\\(\\s*'((?:[^']|'')*)'\\s*::\\s*regclass\\s*\\)\\s*$").matcher(defaultSql);
        if(!m.matches())return null;
        List<SqlNames.Token> t=SqlNames.tokens(m.group(1).replace("''","'")).stream().filter(x->!x.raw().isBlank()).toList();
        if(t.size()==1&&t.getFirst().identifier()!=null)return new Reference(currentSchema,t.getFirst().identifier());
        if(t.size()==3&&t.get(0).identifier()!=null&&t.get(1).raw().equals(".")&&t.get(2).identifier()!=null)return new Reference(t.get(0).identifier(),t.get(2).identifier());
        throw new IllegalArgumentException("无法解析序列引用："+defaultSql);
    }
    static String derivedName(String table,String column){String full=table+"_"+column+"_seq";if(full.getBytes(StandardCharsets.UTF_8).length<=63)return full;
        try{String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(full.getBytes(StandardCharsets.UTF_8))).substring(0,10);StringBuilder prefix=new StringBuilder();for(int cp:full.codePoints().toArray()){String next=new String(Character.toChars(cp));if((prefix+next).getBytes(StandardCharsets.UTF_8).length>52)break;prefix.append(next);}return prefix+"_"+hash;}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
