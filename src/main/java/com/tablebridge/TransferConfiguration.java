package com.tablebridge;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Versioned user configuration; stores object choices, never JDBC credentials or records. */
final class TransferConfiguration {
    record ObjectChoice(String source,String group,String target,boolean custom,boolean data,Map<String,FieldMapping> mappings,String objectSql,Set<String> dependencies,String signature){
        ObjectChoice(String source,String group,String target,boolean custom,boolean data,Map<String,FieldMapping> mappings,String objectSql,Set<String> dependencies){this(source,group,target,custom,data,mappings,objectSql,dependencies,"");}
        ObjectChoice(String source,String group,String target,boolean custom,boolean data){this(source,group,target,custom,data,Map.of(),"",Set.of());}
        ObjectChoice(String source,String group,String target,boolean custom,boolean data,Map<String,FieldMapping> mappings){this(source,group,target,custom,data,mappings,"",Set.of());}
        ObjectChoice(String source,String group,String target,boolean custom,boolean data,Map<String,FieldMapping> mappings,String sql){this(source,group,target,custom,data,mappings,sql,Set.of());}
        ObjectChoice{mappings=Map.copyOf(mappings);objectSql=objectSql==null?"":objectSql;dependencies=Set.copyOf(dependencies);if(objectSql.length()>262144||dependencies.size()>10000)throw new IllegalArgumentException("对象 SQL/依赖超过大小限制");}
    }
    record Settings(String source,String target,boolean records,boolean foreignKeys,boolean continueOnError,ExistingPolicy policy,List<ObjectChoice> objects,boolean verifyContent,boolean recoverable){
        Settings(String source,String target,boolean records,boolean foreignKeys,boolean continueOnError,ExistingPolicy policy,List<ObjectChoice> objects,boolean verifyContent){this(source,target,records,foreignKeys,continueOnError,policy,objects,verifyContent,false);}
        Settings(String source,String target,boolean records,boolean foreignKeys,boolean continueOnError,ExistingPolicy policy,List<ObjectChoice> objects){this(source,target,records,foreignKeys,continueOnError,policy,objects,false,false);}
        Settings{objects=List.copyOf(objects);if(objects.isEmpty()||objects.size()>10000)throw new IllegalArgumentException("配置对象数量须为 1–10000");}
    }
    static void save(Path file,Settings settings)throws IOException{
        Properties p=new Properties();p.setProperty("version","6");p.setProperty("recoverable",Boolean.toString(settings.recoverable));p.setProperty("verifyContent",Boolean.toString(settings.verifyContent));p.setProperty("source",settings.source);p.setProperty("target",settings.target);
        p.setProperty("records",Boolean.toString(settings.records));p.setProperty("foreignKeys",Boolean.toString(settings.foreignKeys));p.setProperty("continueOnError",Boolean.toString(settings.continueOnError));p.setProperty("policy",settings.policy.name());p.setProperty("count",Integer.toString(settings.objects.size()));
        for(int i=0;i<settings.objects.size();i++){ObjectChoice o=settings.objects.get(i);String key="object."+i+".";p.setProperty(key+"objectSql",o.objectSql);p.setProperty(key+"signature",o.signature);p.setProperty(key+"dependencyCount",Integer.toString(o.dependencies.size()));int dependencyIndex=0;for(String dependency:o.dependencies)p.setProperty(key+"dependency."+(dependencyIndex++),dependency);p.setProperty(key+"source",o.source);p.setProperty(key+"group",o.group);p.setProperty(key+"target",o.target);p.setProperty(key+"custom",Boolean.toString(o.custom));p.setProperty(key+"data",Boolean.toString(o.data));p.setProperty(key+"mappingCount",Integer.toString(o.mappings.size()));int n=0;for(var entry:o.mappings.entrySet()){String m=key+"mapping."+(n++)+".";FieldMapping value=entry.getValue();p.setProperty(m+"column",entry.getKey());p.setProperty(m+"engine",value.engine().name());p.setProperty(m+"type",value.type());p.setProperty(m+"omitDefault",Boolean.toString(value.omitDefault()));p.setProperty(m+"materialize",Boolean.toString(value.materialize()));}}
        Path absolute=file.toAbsolutePath();Path temporary=Files.createTempFile(absolute.getParent(),".tablebridge-",".tmp");
        try{try(OutputStream output=Files.newOutputStream(temporary)){p.storeToXML(output,"TableBridge transfer configuration","UTF-8");}if(Files.size(temporary)>2*1024*1024)throw new IOException("配置文件超过 2 MiB");try{Files.move(temporary,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(temporary,absolute,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temporary);}
    }
    static Settings load(Path file)throws IOException{
        if(Files.size(file)>2*1024*1024)throw new IOException("配置文件超过 2 MiB");
        Properties p=new Properties();try(InputStream input=Files.newInputStream(file)){p.loadFromXML(input);}
        try{
            String version=required(p,"version");if(!Set.of("1","2","3","4","5","6").contains(version))throw new IllegalArgumentException("不支持的配置版本");
            int count=Integer.parseInt(required(p,"count"));if(count<1||count>10000)throw new IllegalArgumentException("对象数量不合法");
            List<ObjectChoice> choices=new ArrayList<>();Set<String> seen=new HashSet<>();
            for(int i=0;i<count;i++){String key="object."+i+".";String source=required(p,key+"source"),group=required(p,key+"group");if(!seen.add(group+"\0"+source+"\0"+p.getProperty(key+"signature","")))throw new IllegalArgumentException("对象配置重复："+source);Map<String,FieldMapping> mappings=new LinkedHashMap<>();if(!version.equals("1")){int mappingCount=Integer.parseInt(required(p,key+"mappingCount"));if(mappingCount<0||mappingCount>10000)throw new IllegalArgumentException("字段映射数量无效");for(int n=0;n<mappingCount;n++){String m=key+"mapping."+n+".",column=required(p,m+"column");if(mappings.put(column,new FieldMapping(Engine.valueOf(required(p,m+"engine")),required(p,m+"type"),flag(p,m+"omitDefault"),flag(p,m+"materialize")))!=null)throw new IllegalArgumentException("字段映射重复："+column);}}Set<String> dependencies=new LinkedHashSet<>();if(Set.of("4","5","6").contains(version)){int dependencyCount=Integer.parseInt(p.getProperty(key+"dependencyCount","0"));if(dependencyCount<0||dependencyCount>10000)throw new IllegalArgumentException("依赖数量无效");for(int n=0;n<dependencyCount;n++)if(!dependencies.add(required(p,key+"dependency."+n)))throw new IllegalArgumentException("重复对象依赖");}choices.add(new ObjectChoice(source,group,required(p,key+"target"),flag(p,key+"custom"),flag(p,key+"data"),mappings,Set.of("4","5","6").contains(version)?p.getProperty(key+"objectSql",""):"",dependencies,p.getProperty(key+"signature","")));}
            return new Settings(required(p,"source"),required(p,"target"),flag(p,"records"),flag(p,"foreignKeys"),flag(p,"continueOnError"),ExistingPolicy.valueOf(required(p,"policy")),choices,Set.of("3","4","5","6").contains(version)&&flag(p,"verifyContent"),Set.of("5","6").contains(version)&&flag(p,"recoverable"));
        }catch(IllegalArgumentException ex){throw new IOException("配置无效："+ex.getMessage(),ex);}
    }
    private static String required(Properties p,String name){String value=p.getProperty(name);if(value==null||value.isBlank())throw new IllegalArgumentException("缺少 "+name);return value;}
    private static boolean flag(Properties p,String name){String value=required(p,name);if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException(name+" 必须是 true/false");return Boolean.parseBoolean(value);}
}
