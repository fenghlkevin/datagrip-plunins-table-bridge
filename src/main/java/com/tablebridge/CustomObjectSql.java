package com.tablebridge;

import java.util.*;

/** Validate one user-reviewed CREATE definition bound to the chosen destination. */
final class CustomObjectSql {
    static String validate(Engine target,String namespace,String name,String kind,String sql){
        if(sql==null||sql.isBlank()||sql.length()>262144||sql.indexOf('\0')>=0)throw new IllegalArgumentException("对象 SQL 不能为空且不能超过 256 KiB");
        List<SqlNames.Token> tokens=SqlNames.tokens(sql).stream().filter(t->!t.raw().isBlank()&&!t.raw().startsWith("--")&&!t.raw().startsWith("/*")).toList();
        if(tokens.size()<6||!tokens.get(0).raw().equalsIgnoreCase("create")||!tokens.get(1).raw().equalsIgnoreCase(kind))throw new IllegalArgumentException("只能保存 CREATE "+kind+" 定义；不允许替换或修改其他对象");
        if(!namespace.equals(tokens.get(2).identifier())||!tokens.get(3).raw().equals(".")||!name.equals(tokens.get(4).identifier()))throw new IllegalArgumentException("CREATE 名称必须是 "+target.qualified(namespace,name));
        for(int index:new int[]{2,4}){
            var token=tokens.get(index);String identifier=token.identifier();
            if(token.raw().equals(identifier)){
                String actual=target==Engine.POSTGRES?identifier.toLowerCase(Locale.ROOT):target==Engine.ORACLE?identifier.toUpperCase(Locale.ROOT):identifier;
                if(!actual.equals(identifier))throw new IllegalArgumentException("大小写敏感的目标名称必须使用目标数据库引号");
            }else if(!token.raw().equals(target.quote(identifier)))throw new IllegalArgumentException("目标名称引号不符合当前数据库方言");
        }
        if(kind.equals("SEQUENCE")&&target==Engine.MYSQL||kind.equals("TYPE")&&target==Engine.MYSQL)throw new IllegalArgumentException("MySQL 没有原生 "+kind+" 对象；此入口不会自动仿真或改变对象类别");
        boolean routine=kind.equals("FUNCTION")||kind.equals("PROCEDURE")||kind.equals("TRIGGER");int depth=0;boolean begun=false,skip=false,closed=false;
        for(int i=5;i<tokens.size();i++){
            var token=tokens.get(i);String raw=token.raw().toLowerCase(Locale.ROOT);
            if(token.literal())continue;
            if(skip){skip=false;continue;}
            if(closed&&!raw.equals(";")&&!(name.equals(token.identifier())&&i>0&&tokens.get(i-1).raw().equalsIgnoreCase("end")))throw new IllegalArgumentException("对象定义后包含额外 SQL");
            if(routine&&Set.of("begin","case","if","loop","while","repeat").contains(raw)&&!(raw.equals("if")&&i+1<tokens.size()&&tokens.get(i+1).raw().equals("("))){
                // SQL functions may use CASE expressions; statement terminators must remain inside them.
                depth++;begun=true;
            }else if(routine&&raw.equals("end")){
                if(--depth<0)throw new IllegalArgumentException("过程块 END 不匹配");
                if(i+1<tokens.size()&&Set.of("if","loop","while","repeat","case").contains(tokens.get(i+1).raw().toLowerCase(Locale.ROOT)))skip=true;
                if(depth==0)closed=true;
            }
            if(raw.equals(";")&&depth==0&&i!=tokens.size()-1&&!(routine&&target==Engine.ORACLE&&!begun))throw new IllegalArgumentException("仅允许单个目标对象定义");
        }
        if(depth!=0)throw new IllegalArgumentException("过程块未闭合");
        return sql.trim();
    }
}
