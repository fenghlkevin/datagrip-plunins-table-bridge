package com.tablebridge;

import java.util.*;

/** Conservative references to known objects; ignores comments and ordinary string values. */
final class ObjectDependencies {
    static Set<String> references(String sql,String schema,Set<String> known,String own){
        LinkedHashSet<String> result=new LinkedHashSet<>();scan(sql,schema,known,result,0);result.remove(own);return Set.copyOf(result);
    }
    private static void scan(String sql,String schema,Set<String> known,Set<String> found,int depth){
        if(depth>4)throw new IllegalArgumentException("嵌套程序体过深，请声明手工依赖");
        List<SqlNames.Token> tokens=SqlNames.tokens(sql).stream().filter(t->!t.raw().isBlank()&&!t.raw().startsWith("--")&&!t.raw().startsWith("/*")).toList();
        for(int i=0;i<tokens.size();i++){
            var token=tokens.get(i);if(token.literal()&&token.raw().startsWith("$")){int tag=token.raw().indexOf('$',1);String body=token.raw().substring(tag+1,token.raw().length()-tag-1);scan(body,schema,known,found,depth+1);continue;}
            if(schema.equals(token.identifier())&&i+2<tokens.size()&&tokens.get(i+1).raw().equals(".")&&known.contains(tokens.get(i+2).identifier())){found.add(tokens.get(i+2).identifier());continue;}
            if(token.identifier()!=null&&known.contains(token.identifier())){
                String previous=i==0?"":tokens.get(i-1).raw().toUpperCase(Locale.ROOT);
                boolean call=i+1<tokens.size()&&tokens.get(i+1).raw().equals("(");
                if(Set.of("FROM","JOIN","REFERENCES","CALL","UPDATE","INTO","TYPE").contains(previous)||call)found.add(token.identifier());
            }
        }
    }
}
