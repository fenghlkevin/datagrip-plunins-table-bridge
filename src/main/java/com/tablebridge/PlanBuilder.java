package com.tablebridge;

import com.intellij.database.model.*;
import com.intellij.database.model.basic.*;
import com.intellij.database.psi.DbElement;
import com.intellij.database.script.generator.*;
import com.intellij.database.util.*;
import com.intellij.openapi.project.Project;
import java.util.*;
import static com.intellij.database.script.generator.ScriptingOptionStatic.*;

final class PlanBuilder {
    record Choice(DbElement source,String targetName,boolean data,Map<String,FieldMapping> mappings,String objectSql,Set<String> dependencies){
        Choice(DbElement source,String targetName,boolean data){this(source,targetName,data,Map.of(),"",Set.of());}
        Choice(DbElement source,String targetName,boolean data,Map<String,FieldMapping> mappings){this(source,targetName,data,mappings,"",Set.of());}
        Choice(DbElement source,String targetName,boolean data,Map<String,FieldMapping> mappings,String sql){this(source,targetName,data,mappings,sql,Set.of());}
        Choice{mappings=Map.copyOf(mappings);objectSql=objectSql==null?"":objectSql;dependencies=Set.copyOf(dependencies);}
    }
    static TransferPlan build(Project project,List<Choice> choices,DbElement target,boolean continueOnError,boolean foreignKeys) {
        return build(project,choices,target,continueOnError,foreignKeys,ExistingPolicy.FAIL);
    }
    static TransferPlan build(Project project,List<Choice> choices,DbElement target,boolean continueOnError,boolean foreignKeys,ExistingPolicy policy) {
        if(choices.isEmpty())throw new IllegalArgumentException("请至少选择一个对象");
        DbElement sourceNs=DbSelection.namespace(choices.getFirst().source());Engine engine=DbSelection.engine(sourceNs);
        Engine targetEngine=DbSelection.engine(target);boolean cross=engine!=targetEngine;
        if(!CrossEngineDdl.supports(engine,targetEngine))throw new IllegalArgumentException("跨类型支持 MySQL、PostgreSQL 和 Oracle");
        List<String> warnings=new ArrayList<>();
        if(!cross&&engine==Engine.POSTGRES)choices=withSequences(choices,sourceNs,target,policy,warnings);
        if(cross&&choices.stream().anyMatch(c->c.source.getKind()==ObjectKind.TABLE))warnings.add("跨类型表转换：字段、主键、自增和记录；普通/唯一索引和外键按目标方言生成；检查约束、排序规则和触发器仍需审核。请审核字段映射与 SQL。");
        if(DbImplUtilCore.getLocalDataSource(target).isReadOnly())throw new IllegalArgumentException("目标连接为只读");
        Map<String,String> rename=new LinkedHashMap<>();Set<String> destinations=new HashSet<>(),sourceNames=new HashSet<>();
        for(Choice c:choices){
            if(!c.source.isValid()||!Objects.equals(sourceNs,DbSelection.namespace(c.source)))throw new IllegalArgumentException("来源对象已失效或不属于同一模式，请重新复制");
            targetEngine.quote(c.targetName);
            if(!sourceNames.add(ObjectIdentity.key(c.source)))throw new IllegalArgumentException("来源对象重复："+c.source.getName());
            if(cross&&c.objectSql.isBlank()&&c.source.getKind()!=ObjectKind.TABLE&&c.source.getKind()!=ObjectKind.VIEW&&c.source.getKind()!=ObjectKind.SEQUENCE&&c.source.getKind()!=ObjectKind.OBJECT_TYPE&&c.source.getKind()!=ObjectKind.ROUTINE)throw new IllegalArgumentException("该对象需要手工目标定义；请打开高级 → 转换定义："+c.source.getName());
            if(!destinations.add(c.targetName.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("目标名称重复："+c.targetName+"（暂不支持同名重载对象）");
            if(sourceNs.equals(target)&&c.source.getName().equals(c.targetName))throw new IllegalArgumentException("同一模式中复制必须修改目标名称");
            rename.put(c.source.getName(),c.targetName);
            for(var existing:DbSelection.basic(target).getChildren())if(existing.getName().equalsIgnoreCase(c.targetName)){if(policy==ExistingPolicy.FAIL)throw new IllegalArgumentException("目标对象已存在："+c.targetName+"。请改名或选择目标已存在时的处理方式。");if(c.source.getKind()!=ObjectKind.TABLE||existing.getKind()!=ObjectKind.TABLE)throw new IllegalArgumentException("已有对象处理目前只支持表："+c.targetName);if(!c.data&&(policy==ExistingPolicy.APPEND||policy==ExistingPolicy.CLEAR))throw new IllegalArgumentException("追加或清空复制必须勾选表数据："+c.targetName);}
        }
        Set<String> overloaded=new HashSet<>();Map<String,Long> counts=choices.stream().collect(java.util.stream.Collectors.groupingBy(c->c.source.getName(),java.util.stream.Collectors.counting()));counts.forEach((name,n)->{if(n>1)overloaded.add(name);});
        for(Choice c:choices)if(overloaded.contains(c.source.getName())){if(c.source.getKind()!=ObjectKind.ROUTINE||c.objectSql.isBlank())throw new IllegalArgumentException("批量重载对象请为每个签名指定不同目标名称及手工目标定义："+c.source.getName());warnings.add("重载签名："+c.source.getName()+" "+ObjectIdentity.signature(c.source)+" → "+c.targetName);}
        if(foreignKeys)validateForeignKeys(choices,target,rename);
        List<TransferPlan.Item> items=new ArrayList<>();
        Map<String,PortableView.Converted> views=new LinkedHashMap<>();
        if(cross)for(Choice choice:choices)if(choice.source.getKind()==ObjectKind.VIEW&&choice.objectSql.isBlank()){
            var basic=DbSelection.basic(choice.source);
            if(!(basic instanceof BasicSourceAware body)||body.isObfuscated()||body.getSourceText()==null||basic.isOutdated())throw new IllegalArgumentException("请先打开视图 DDL 并刷新："+choice.source.getName());
            views.put(choice.source.getName(),PortableView.convert(engine,targetEngine,sourceNs.getName(),target.getName(),choice.targetName,body.getSourceText().getText().toString(),rename));
        }
        List<Choice> ordered=new ArrayList<>(choices);ordered.sort(Comparator.comparingInt(c->priority(c.source.getKind())));
        Map<String,Set<String>> graph=new LinkedHashMap<>();
        Set<String> known=new LinkedHashSet<>();for(var object:DbSelection.basic(sourceNs).getChildren())if(Set.of(ObjectKind.TABLE,ObjectKind.VIEW,ObjectKind.ROUTINE,ObjectKind.SEQUENCE,ObjectKind.OBJECT_TYPE).contains(object.getKind()))known.add(object.getName());
        Set<String> existingNames=new HashSet<>();for(var object:DbSelection.basic(target).getChildren())existingNames.add(object.getName());
        for(Choice choice:ordered){Set<String> dependencies=new LinkedHashSet<>(choice.dependencies);if(views.containsKey(choice.source.getName()))dependencies.addAll(views.get(choice.source.getName()).dependencies());
            BasicElement element=DbSelection.basic(choice.source);
            if(choice.objectSql.isBlank()&&choice.source.getKind()==ObjectKind.ROUTINE&&element instanceof BasicSourceAware body&&body.getSourceText()!=null&&java.util.regex.Pattern.compile("(?is)\\b(EXECUTE\\s+IMMEDIATE|EXECUTE|PREPARE)\\b").matcher(body.getSourceText().getText()).find())throw new IllegalArgumentException("动态 SQL 依赖需审核，请在高级转换定义中检查目标引用并声明依赖："+choice.source.getName());
            if(element instanceof BasicSourceAware body&&body.getSourceText()!=null&&!body.isObfuscated())dependencies.addAll(ObjectDependencies.references(body.getSourceText().getText().toString(),sourceNs.getName(),known,choice.source.getName()));
            for(var child:element.getChildren())if(child.getKind()==ObjectKind.TRIGGER&&child instanceof BasicSourceAware body&&body.getSourceText()!=null&&!body.isObfuscated())dependencies.addAll(ObjectDependencies.references(body.getSourceText().getText().toString(),sourceNs.getName(),known,choice.source.getName()));
            if(dependencies.stream().anyMatch(overloaded::contains))throw new IllegalArgumentException("依赖调用存在重载歧义，请在目标定义明确签名并分别迁移依赖："+dependencies);
            Set<String> missing=new LinkedHashSet<>(dependencies);missing.removeAll(rename.keySet());missing.removeAll(existingNames);if(!missing.isEmpty())throw new IllegalArgumentException("请补选对象依赖或先在目标创建："+missing);
            dependencies.retainAll(rename.keySet());
            Set<String> ids=new LinkedHashSet<>();for(String dependency:dependencies)for(Choice candidate:ordered)if(candidate.source.getName().equals(dependency))ids.add(ObjectIdentity.key(candidate.source));graph.put(ObjectIdentity.key(choice.source),ids);
        }
        List<String> creationOrder=PortableView.order(graph);ordered.sort(Comparator.comparingInt(c->creationOrder.indexOf(ObjectIdentity.key(c.source))));
        for(Choice c:ordered){
            BasicElement b=DbSelection.basic(c.source);boolean table=b.getKind()==ObjectKind.TABLE;
            if(b.isOutdated())throw new IllegalArgumentException("请先刷新来源对象："+b.getName());
            boolean exists=false;for(var existing:DbSelection.basic(target).getChildren())if(existing.getName().equalsIgnoreCase(c.targetName))exists=true;
            ExistingPolicy action=exists?policy:ExistingPolicy.FAIL;
            if(table&&(policy==ExistingPolicy.UPSERT||policy==ExistingPolicy.MIRROR)){
                if(!c.data)throw new IllegalArgumentException("增量/同步必须勾选表数据");
                if(!exists)throw new IllegalArgumentException("增量/同步要求目标表已存在；请先复制结构："+c.targetName);
                if(!c.objectSql.isBlank())throw new IllegalArgumentException("增量/同步保留目标结构，请清空手工 CREATE 定义");
                var key=DasUtil.getPrimaryKey(c.source);if(key==null||!key.getColumnsRef().names().iterator().hasNext())throw new IllegalArgumentException("增量/同步要求来源主键："+c.source.getName());
                var dataColumns=cross?crossTableColumns(c,target,targetEngine,engine):columns(c,engine);
                if(!dataColumns.stream().map(TransferPlan.Column::name).toList().containsAll(names(key.getColumnsRef().names())))throw new IllegalArgumentException("主键必须在复制字段中");
                items.add(new TransferPlan.Item(c.source,c.targetName,true,List.of(),List.of(),dataColumns,policy));warnings.add("按主键新增/更新"+(policy==ExistingPolicy.MIRROR?"并删除目标独有记录":"；保留目标独有记录")+"："+c.targetName);continue;
            }
            if(!cross&&!c.mappings.isEmpty())throw new IllegalArgumentException("手工字段映射仅适用于跨类型复制："+c.targetName);
            if(!table&&!c.objectSql.isBlank()){
                String custom=CustomObjectSql.validate(targetEngine,target.getName(),c.targetName,objectSqlKind(c.source),c.objectSql);
                items.add(new TransferPlan.Item(c.source,c.targetName,false,List.of(custom),List.of(),List.of(),ExistingPolicy.FAIL));
                warnings.add("手工目标定义："+c.source.getName()+"；请审核引用与执行权限，手工依赖顺序仍需确认。");continue;
            }
            if(cross&&Set.of(ObjectKind.ROUTINE,ObjectKind.OBJECT_TYPE,ObjectKind.SEQUENCE).contains(c.source.getKind())){
                String converted=automaticObjectDefinition(c.source,c.targetName,target,rename);
                items.add(new TransferPlan.Item(c.source,c.targetName,false,List.of(converted),List.of(),List.of(),ExistingPolicy.FAIL));
                warnings.add("非表对象 "+c.source.getName()+" 已转换；请审核调用、权限和字段语义。序列使用内省 next 值，需暂停写入并刷新。");continue;
            }
            if(cross&&c.source.getKind()==ObjectKind.VIEW){
                items.add(new TransferPlan.Item(c.source,c.targetName,false,List.of(views.get(c.source.getName()).sql()),List.of(),List.of(),ExistingPolicy.FAIL));
                warnings.add("视图 "+c.source.getName()+" 的依赖已限定到目标模式，请审核 SELECT 字段语义。");continue;
            }
            if(table&&!c.objectSql.isBlank()){
                if(action==ExistingPolicy.APPEND||action==ExistingPolicy.CLEAR)throw new IllegalArgumentException("手工完整结构仅适用于新建或重建表");
                var structure=TableStructureSql.validate(targetEngine,target.getName(),c.targetName,c.objectSql);
                List<String> before=new ArrayList<>();if(action==ExistingPolicy.RECREATE)before.add("DROP TABLE "+targetEngine.qualified(target.getName(),c.targetName));before.add(structure.create());
                if(c.data&&engine==Engine.ORACLE&&DasUtil.getColumns(c.source).toList().stream().anyMatch(col->DasUtil.isAuto(col)))throw new IllegalArgumentException("Oracle identity 数据请使用自动结构以安排生成策略恢复；手工结构暂仅复制定义");
                List<TransferPlan.Column> dataColumns=cross?crossTableColumns(c,target,targetEngine,engine):columns(c,engine);
                items.add(new TransferPlan.Item(c.source,c.targetName,c.data,before,structure.after(),c.data?dataColumns:List.of(),action,true));
                warnings.add("手工完整结构："+c.source.getName()+"；排序规则、索引、触发器及引用请审核。表先创建，数据后创建附属结构。");continue;
            }
            if(cross){
                if(action!=ExistingPolicy.APPEND&&action!=ExistingPolicy.CLEAR)for(String original:ddl(project,b,ScriptCategory.CREATE_COMPLETE,foreignKeys,engine,sourceNs,sourceNs,Map.of()))TableStructureSql.requirePortableSource(original);
                items.add(crossTable(c,target,targetEngine,engine,action,foreignKeys,rename,warnings));continue;}
            if(action==ExistingPolicy.APPEND||action==ExistingPolicy.CLEAR){List<TransferPlan.Column> columns=columns(c,engine);items.add(new TransferPlan.Item(c.source,c.targetName,true,action==ExistingPolicy.CLEAR?List.of("DELETE FROM "+targetEngine.qualified(target.getName(),c.targetName)):List.of(),List.of(),columns,action));continue;}
            if(b.isOutdated())throw new IllegalArgumentException("请先刷新来源对象："+b.getName());
            if(b instanceof BasicSourceAware body && (body.isObfuscated()||body.getSourceTextLength()==0))throw new IllegalArgumentException("对象定义未加载或已加密，请先在 DataGrip 打开 DDL："+b.getName());
            for(var child:b.getChildren())if(child.getKind()==ObjectKind.TRIGGER&&(child.isOutdated()||child instanceof BasicSourceAware body&&(body.isObfuscated()||body.getSourceTextLength()==0)))throw new IllegalArgumentException("请先加载并刷新触发器定义："+child.getName());
            List<String> generated=ddl(project,b,ScriptCategory.CREATE_COMPLETE,foreignKeys,engine,sourceNs,target,identityNames(c,engine,rename,target));
            generated.forEach(TableStructureSql::requireAutomaticTriggerReferences);
            if(generated.stream().anyMatch(CrossStructure::isTrigger))warnings.add("触发器在数据和外键后创建；请确认触发器函数、审计表等依赖已迁移或存在于目标。");
            List<String> before=new ArrayList<>(),after=new ArrayList<>();
            for(String statement:generated){
                String normalized=statement.stripLeading().toLowerCase(Locale.ROOT);
                if((!table&&normalized.startsWith("alter sequence ")&&normalized.contains("owned by")) || (table && (normalized.startsWith("alter table ") || normalized.matches("(?s)^create\\s+(unique\\s+)?index\\b.*") || CrossStructure.isTrigger(statement))))after.add(statement);else before.add(statement);
            }
            if(table&&c.data&&engine==Engine.ORACLE){var identityColumns=DasUtil.getColumns(c.source).toList().stream().filter(DasUtil::isAuto).map(DasColumn::getName).toList();for(int j=0;j<before.size();j++)if(before.get(j).stripLeading().toLowerCase(Locale.ROOT).startsWith("create table")){var identity=OracleIdentity.prepare(before.get(j),identityColumns,engine.qualified(target.getName(),c.targetName));before.set(j,identity.create());after.addAll(identity.after());}if(!identityColumns.isEmpty())warnings.add("Oracle identity：导入后恢复生成策略并重置至记录边界；Oracle 实库未验收。");}
            if(before.isEmpty())throw new IllegalArgumentException("未生成创建语句："+b.getName());
            String expected=engine.qualified(target.getName(),c.targetName);
            if(before.stream().noneMatch(sql->sql.contains(expected)))
                throw new IllegalArgumentException("生成的创建语句未明确指向目标对象："+expected);
            List<TransferPlan.Column> columns=new ArrayList<>();
            if(table&&c.data)for(var column:DasUtil.getColumns(c.source)){
                if(DasUtil.isComputed(column))continue;
                if(engine==Engine.MSSQL && Set.of("timestamp","rowversion").contains(column.getDasType().getSpecification().toLowerCase(Locale.ROOT)))continue;

                columns.add(new TransferPlan.Column(column.getName(),identity(column,engine)));
            }
            if(table&&c.data&&columns.isEmpty())throw new IllegalArgumentException("没有可写入字段："+b.getName());
            if(action==ExistingPolicy.RECREATE)before.addFirst("DROP TABLE "+targetEngine.qualified(target.getName(),c.targetName));
            items.add(new TransferPlan.Item(c.source,c.targetName,table&&c.data,before,after,List.copyOf(columns),action));
        }
        var plan=new TransferPlan(sourceNs,target,engine,targetEngine,items,continueOnError,warnings);plan.recoverable=policy==ExistingPolicy.UPSERT||policy==ExistingPolicy.MIRROR;return plan;
    }
    private static Map<String,String> identityNames(Choice choice,Engine engine,Map<String,String> rename,DbElement target){
        Map<String,String> names=new LinkedHashMap<>(rename);if(engine!=Engine.POSTGRES)return names;
        Set<String> used=new HashSet<>(names.values());for(var e:DbSelection.basic(target).getChildren())used.add(e.getName());
        for(var column:DasUtil.getColumns(choice.source))if(DasUtil.isAuto(column)&&column instanceof com.intellij.database.dialects.postgresgreenplumbase.model.PgGPlumBaseLocalTableColumn pg&&pg.getSequenceRef()!=null){
            String old=pg.getSequenceRef().getName();if(old==null||old.isBlank())throw new IllegalArgumentException("identity 序列引用未加载："+column.getName());
            if(rename.containsKey(old))throw new IllegalArgumentException("identity 内部序列随表创建，请取消单独选择序列："+old);
            String name=PostgresSequences.derivedName(choice.targetName,column.getName());int suffix=2;while(used.contains(name))name=PostgresSequences.derivedName(choice.targetName,column.getName()+"_"+suffix++);used.add(name);names.put(old,name);
        }
        return names;
    }
    private static boolean identity(DasColumn column,Engine engine){return DasUtil.isAuto(column)||(engine==Engine.POSTGRES&&PostgresSequences.reference(column.getDefault(),DasUtil.getSchema(column))!=null);}
    private static List<TransferPlan.Column> columns(Choice c,Engine engine){
        List<TransferPlan.Column> columns=new ArrayList<>();for(var column:DasUtil.getColumns(c.source)){
            if(DasUtil.isComputed(column))continue;
            if(engine==Engine.MSSQL&&Set.of("timestamp","rowversion").contains(column.getDasType().getSpecification().toLowerCase(Locale.ROOT)))continue;
            if(engine==Engine.ORACLE&&DasUtil.isAuto(column))throw new IllegalArgumentException("Oracle identity 数据暂需手工处理："+c.source.getName());
            columns.add(new TransferPlan.Column(column.getName(),identity(column,engine)));
        }if(columns.isEmpty())throw new IllegalArgumentException("没有可写入字段："+c.source.getName());return List.copyOf(columns);
    }
    static String automaticObjectDefinition(DbElement source,String name,DbElement target,Map<String,String> names){
        var b=DbSelection.basic(source);Engine from=DbSelection.engine(source),to=DbSelection.engine(target);
        if(b.isOutdated())throw new IllegalArgumentException("请刷新来源对象："+b.getName());
        if(from==to)throw new IllegalArgumentException("此入口仅用于跨类型转换");
        if(source.getKind()==ObjectKind.VIEW){
            if(!(b instanceof BasicSourceAware body)||body.isObfuscated()||body.getSourceText()==null)throw new IllegalArgumentException("请先打开视图 DDL："+b.getName());
            return PortableView.convert(from,to,DbSelection.namespace(source).getName(),target.getName(),name,body.getSourceText().getText().toString(),names).sql();
        }
        if(source.getKind()==ObjectKind.ROUTINE){
                if(!(b instanceof BasicRoutine routine)||routine.getRoutineKind().name().contains("PROCEDURE"))throw new IllegalArgumentException("存储过程的语句与事务语义需要手工转换："+b.getName());
                if(routine.isObfuscated()||routine.getSourceText()==null)throw new IllegalArgumentException("请先打开函数 DDL："+b.getName());
                List<PortableRoutine.Argument> arguments=new ArrayList<>();
                for(var argument:routine.getArguments()){
                    if(argument.getArgumentDirection().isReturnOrResult())continue;
                    if(argument.getArgumentDirection()!=ArgumentDirection.IN||argument.isVariadic()||argument.isDefaulted())throw new IllegalArgumentException("函数 OUT/可变/默认参数需手工转换："+argument.getName());
                    arguments.add(new PortableRoutine.Argument(argument.getName(),routineType(argument)));
                }
                var returned=routine.getReturnArgument();if(returned==null)throw new IllegalArgumentException("函数返回值未加载："+b.getName());
                String sql=PortableRoutine.create(from,to,target.getName(),name,arguments,routineType(returned),routine.getSourceText().getText().toString());
                return sql;
            }
        if(source.getKind()==ObjectKind.OBJECT_TYPE){
                List<CrossEngineDdl.Column> attributes=new ArrayList<>();
                for(var attribute:b.getChildren()){
                    if(attribute.getKind()!=ObjectKind.OBJECT_ATTRIBUTE||!(attribute instanceof BasicTypedElement typed))throw new IllegalArgumentException("对象类型含方法或未支持的属性，需手工转换："+b.getName());
                    DataType type=typed.getDataType();attributes.add(new CrossEngineDdl.Column(attribute.getName(),type.getSpecification(),type.jdbcType,type.size,type.scale,false,false,null,false));
                }
                return PortableType.create(from,to,target.getName(),name,attributes);
            }
        if(source.getKind()==ObjectKind.SEQUENCE){
                if(!(b instanceof BasicSequence sequence))throw new IllegalArgumentException("序列元数据未加载："+source.getName());
                var identity=sequence.getSequenceIdentity();
                if(identity==null)throw new IllegalArgumentException("序列边界未加载："+source.getName());
                var spec=new PortableSequence.Spec(identity.next,identity.increment,identity.min,identity.max,sequence.getCacheSize(),sequence.isCycled());
                return PortableSequence.create(to,target.getName(),name,spec);
            }
        throw new IllegalArgumentException("此对象需要手工转换目标定义："+b.getName());
    }
    static String sourceDefinition(Project project,DbElement source){
        var basic=DbSelection.basic(source);
        if(basic instanceof BasicSourceAware body&&body.getSourceText()!=null)return body.getSourceText().getText().toString();
        var namespace=DbSelection.namespace(source);var engine=DbSelection.engine(source);
        return String.join("\n\n",ddl(project,basic,ScriptCategory.CREATE_COMPLETE,false,engine,namespace,namespace,Map.of(source.getName(),source.getName())));
    }
    static String objectSqlKind(DbElement source){
        if(source.getKind()==ObjectKind.VIEW)return "VIEW";
        if(source.getKind()==ObjectKind.SEQUENCE)return "SEQUENCE";
        if(source.getKind()==ObjectKind.ROUTINE)return DbSelection.group(source).equals("存储过程")?"PROCEDURE":"FUNCTION";
        return "TYPE";
    }
    private static CrossEngineDdl.Column routineType(BasicTypedElement element){
        DataType type=element.getDataType();return new CrossEngineDdl.Column(element.getName(),type.getSpecification(),type.jdbcType,type.size,type.scale,false,false,null,false);
    }
    private static void requirePortableIndexMetadata(DasIndex index){
        boolean manual=false;
        if(index instanceof com.intellij.database.dialects.mysqlbase.model.MysqlBaseIndex my)manual=my.getPrefixLengths().stream().anyMatch(n->n!=null&&n>0)||(my.getType()!=null&&!my.getType().isBlank()&&!my.getType().equalsIgnoreCase("BTREE"));
        if(index instanceof com.intellij.database.dialects.postgresgreenplumbase.model.PgGPlumBaseIndex pg)manual=!pg.getIncludedColNames().isEmpty()||pg.getClassRefs().stream().anyMatch(Objects::nonNull)||pg.getCollationRefs().stream().anyMatch(Objects::nonNull);
        if(index instanceof com.intellij.database.dialects.postgres.model.PgIndex pg&&pg.isNullsNotDistinct())manual=true;
        if(index instanceof com.intellij.database.dialects.oracle.model.OraIndex ora&&(ora.isBitmap()||ora.getIndextypeRef()!=null))manual=true;
        if(manual)throw new IllegalArgumentException("高级索引属性需在高级 → 完整结构中转换并审核："+index.getName());
    }
    private static Map<String,String> structureNames(BasicElement table,String targetName,Engine engine,Map<String,String> selected){
        Map<String,String> names=new LinkedHashMap<>();if(engine==Engine.MSSQL)return names;
        for(var child:table.getChildren())if(child.getKind()==ObjectKind.TRIGGER||child.getKind()==ObjectKind.INDEX){
            if(child.getKind()==ObjectKind.INDEX&&engine==Engine.MYSQL)continue;
            if(child.getKind()==ObjectKind.TRIGGER&&engine==Engine.POSTGRES)continue;
            names.put(child.getName(),PostgresSequences.derivedName(targetName,child.getName()));
        }
        return names;
    }
    private static boolean hasTrigger(BasicElement table){for(var child:table.getChildren())if(child.getKind()==ObjectKind.TRIGGER)return true;return false;}
    private static List<TransferPlan.Column> crossTableColumns(Choice c,DbElement target,Engine to,Engine from){
        List<CrossEngineDdl.Column> cols=new ArrayList<>();for(var col:DasUtil.getColumns(c.source)){DataType type=col.getDataType();cols.add(new CrossEngineDdl.Column(col.getName(),type.getSpecification(),type.jdbcType,type.size,type.scale,col.isNotNull(),identity(col,from),col.getDefault(),DasUtil.isComputed(col)));}
        var key=DasUtil.getPrimaryKey(c.source);return CrossEngineDdl.build(from,to,target.getName(),c.targetName,cols,key==null?List.of():names(key.getColumnsRef().names()),c.mappings).columns();
    }
    static String structurePreview(Project project,Choice c,DbElement target,boolean foreignKeys,Map<String,String> names){
        Engine from=DbSelection.engine(c.source),to=DbSelection.engine(target);
        List<String> scripts;
        if(from==to)scripts=ddl(project,DbSelection.basic(c.source),ScriptCategory.CREATE_COMPLETE,foreignKeys,from,DbSelection.namespace(c.source),target,identityNames(c,from,names,target));
        else{for(String original:ddl(project,DbSelection.basic(c.source),ScriptCategory.CREATE_COMPLETE,foreignKeys,from,DbSelection.namespace(c.source),DbSelection.namespace(c.source),Map.of()))TableStructureSql.requirePortableSource(original);var item=crossTable(c,target,to,from,ExistingPolicy.FAIL,foreignKeys,names,new ArrayList<>());scripts=new ArrayList<>(item.before());scripts.addAll(item.after());}
        return String.join("\n\n"+TableStructureSql.SEPARATOR+"\n\n",scripts);
    }
    private static TransferPlan.Item crossTable(Choice c,DbElement target,Engine to,Engine from,ExistingPolicy action,boolean foreignKeys,Map<String,String> rename,List<String> warnings){
        if(action!=ExistingPolicy.APPEND&&action!=ExistingPolicy.CLEAR&&hasTrigger(DbSelection.basic(c.source)))throw new IllegalArgumentException("跨库触发器需在高级 → 完整结构中转换并审核："+c.source.getName());
        List<CrossEngineDdl.Column> columns=new ArrayList<>();
        Map<String,String> comments=new LinkedHashMap<>();
        for(var col:DasUtil.getColumns(c.source))if(col.getComment()!=null&&!col.getComment().isEmpty())comments.put(col.getName(),col.getComment());
        for(var col:DasUtil.getColumns(c.source)){DataType type=col.getDataType();columns.add(new CrossEngineDdl.Column(col.getName(),type.getSpecification(),type.jdbcType,type.size,type.scale,col.isNotNull(),identity(col,from),col.getDefault(),DasUtil.isComputed(col)));}
        var pk=DasUtil.getPrimaryKey(c.source);List<String> primary=new ArrayList<>();if(pk!=null)pk.getColumnsRef().names().forEach(primary::add);
        var definition=CrossEngineDdl.build(from,to,target.getName(),c.targetName,columns,primary,c.mappings,comments);warnings.addAll(definition.warnings());
        List<String> before=new ArrayList<>();String table=to.qualified(target.getName(),c.targetName);
        if(action==ExistingPolicy.RECREATE)before.add("DROP TABLE "+table);
        if(action==ExistingPolicy.CLEAR)before.add("DELETE FROM "+table);
        if(action!=ExistingPolicy.CLEAR&&action!=ExistingPolicy.APPEND)before.add(definition.sql());
        List<String> after=new ArrayList<>();
        if(action!=ExistingPolicy.CLEAR&&action!=ExistingPolicy.APPEND){
            if(to==Engine.ORACLE&&c.data){var identities=definition.columns().stream().filter(TransferPlan.Column::identity).map(TransferPlan.Column::name).toList();after.addAll(OracleIdentity.prepare(definition.sql(),identities,table).after());}
            for(var index:DasUtil.getIndices(c.source)){
                requirePortableIndexMetadata(index);
                String condition=index instanceof BasicIndex basicIndex?basicIndex.getCondition():null;
                List<String> fields=names(index.getColumnsRef().names());
                // Do not duplicate a primary key's backing index.
                if(pk!=null&&index.isUnique()&&fields.equals(primary))continue;
                String name=to==Engine.MYSQL?index.getName():PostgresSequences.derivedName(c.targetName,index.getName());
                after.add(CrossStructure.portableIndex(to,target.getName(),c.targetName,new CrossStructure.Index(name,fields,fields.stream().map(f->index.getColumnSorting(f)==DasIndex.Sorting.DESCENDING).toList(),index.isUnique()),condition,new HashSet<>(columns.stream().map(CrossEngineDdl.Column::name).toList())));
            }
            if(foreignKeys&&c.source.getDelegate() instanceof DasTable sourceTable)for(var fk:DasUtil.getForeignKeys(sourceTable)){
                String name=PostgresSequences.derivedName(c.targetName,fk.getName());
                after.add(CrossStructure.foreignKey(to,target.getName(),c.targetName,new CrossStructure.ForeignKey(name,names(fk.getColumnsRef().names()),rename.getOrDefault(fk.getRefTableName(),fk.getRefTableName()),names(fk.getRefColumns().names()),fk.getDeleteRule()==null?null:fk.getDeleteRule().name(),fk.getUpdateRule()==null?null:fk.getUpdateRule().name())));
            }
            if(c.source.getDelegate() instanceof BasicTableOrView basicTable){
                Set<String> columnNames=new HashSet<>();columns.forEach(column->columnNames.add(column.name()));
                for(var constraint:basicTable.getChecks())after.add(CrossStructure.check(to,target.getName(),c.targetName,PostgresSequences.derivedName(c.targetName,constraint.getName()),constraint.getPredicate(),columnNames));
            }
            if(to!=Engine.MYSQL)comments.forEach((column,text)->after.add(CrossStructure.comment(to,target.getName(),c.targetName,column,text)));
            String comment=DbSelection.basic(c.source).getComment();if(comment!=null&&!comment.isBlank())after.add(CrossStructure.comment(to,target.getName(),c.targetName,null,comment));
        }
        return new TransferPlan.Item(c.source,c.targetName,c.data,List.copyOf(before),List.copyOf(after),definition.columns(),action);
    }
    private static List<Choice> withSequences(List<Choice> choices,DbElement source,DbElement target,ExistingPolicy policy,List<String> warnings){
        List<Choice> result=new ArrayList<>(choices);Map<String,Choice> selected=new HashMap<>();choices.forEach(c->selected.put(c.source.getName(),c));
        for(Choice c:choices)if(c.source.getKind()==ObjectKind.TABLE){
            boolean existing=false;for(var e:DbSelection.basic(target).getChildren())if(e.getName().equalsIgnoreCase(c.targetName))existing=true;
            if(existing&&(policy==ExistingPolicy.APPEND||policy==ExistingPolicy.CLEAR))continue;
            for(var col:DasUtil.getColumns(c.source)){var ref=PostgresSequences.reference(col.getDefault(),source.getName());if(ref==null)continue;
                if(!ref.schema().equals(source.getName()))throw new IllegalArgumentException("跨模式序列依赖需手工处理："+ref.schema()+"."+ref.name());
                if(selected.containsKey(ref.name()))continue;
                DbElement sequence=null;for(var e:DbSelection.basic(source).getChildren())if(e.getKind()==ObjectKind.SEQUENCE&&e.getName().equals(ref.name()))sequence=source.getDataSource().findElement(e);
                if(sequence==null)throw new IllegalArgumentException("请先内省序列依赖："+ref.name());
                String base=PostgresSequences.derivedName(c.targetName,col.getName()),name=base;Set<String> used=new HashSet<>();for(var e:DbSelection.basic(target).getChildren())used.add(e.getName());result.forEach(r->used.add(r.targetName));int suffix=2;while(used.contains(name))name=PostgresSequences.derivedName(c.targetName,col.getName()+"_"+suffix++);Choice extra=new Choice(sequence,name,false);selected.put(ref.name(),extra);result.add(extra);warnings.add("自动加入序列依赖："+ref.name()+" → "+name);
            }
        }return result;
    }
    private static void validateForeignKeys(List<Choice> choices,DbElement target,Map<String,String> rename){
        Set<String> available=new HashSet<>(rename.values());for(var e:DbSelection.basic(target).getChildren())if(e.getKind()==ObjectKind.TABLE)available.add(e.getName());
        for(Choice c:choices)if(c.source.getDelegate() instanceof DasTable table)for(var fk:DasUtil.getForeignKeys(table)){
            String sourceSchema=DbSelection.namespace(c.source).getName();
            if(fk.getRefTableSchema()!=null&&!fk.getRefTableSchema().isBlank()&&!fk.getRefTableSchema().equals(sourceSchema))throw new IllegalArgumentException("外键引用其他模式，需手工处理："+fk.getName());
            if(!available.contains(rename.getOrDefault(fk.getRefTableName(),fk.getRefTableName())))throw new IllegalArgumentException("缺少外键依赖表："+fk.getRefTableName()+"。请点击补选依赖，或先在目标创建。");
        }
    }
    private static List<String> names(Iterable<String> values){List<String> result=new ArrayList<>();values.forEach(result::add);return List.copyOf(result);}
    private static int priority(ObjectKind k){if(DbSelection.KINDS.contains(k)&&k!=ObjectKind.TABLE&&k!=ObjectKind.VIEW&&k!=ObjectKind.ROUTINE&&k!=ObjectKind.SEQUENCE)return 0;if(k==ObjectKind.SEQUENCE)return 1;if(k==ObjectKind.TABLE)return 2;if(k==ObjectKind.ROUTINE)return 3;return 4;}
    private static List<String> ddl(Project project,BasicElement e,ScriptCategory category,boolean foreignKeys,Engine engine,DbElement source,DbElement target,Map<String,String> names){
        var task=new ScriptingSingleModelTaskBuilder(e.getModel(),category);task.setElements(List.of(e));task.setCurrentDatabase(e.getDatabase());task.setVersion(target.getDataSource().getVersion());
        Map<ScriptingOption<?>,Object> options=new HashMap<>();
        options.put(CONSIDER_SCHEMA_NAME,AutoNeverAlways.ALWAYS);options.put(CREATE_OR_REPLACE,false);options.put(CREATE_IF_NOT_EXISTS,false);options.put(ALLOW_RECREATE,false);
        options.put(IGNORE_OWNER,true);options.put(IGNORE_DEFINER,true);options.put(SKIP_GRANTS,true);options.put(CONSIDER_TRIGGERS,engine!=Engine.MSSQL);options.put(CONSIDER_FOREIGN_KEYS,foreignKeys);options.put(USE_SEMICOLON,false);options.put(REFORMAT,false);
        task.setOptions(new CustomScriptingOptions(options));
        List<String> scripts=new ArrayList<>();
        for(String sql:ScriptGenerators.INSTANCE.makeScript(project,task.build()).getScriptStatementsTexts()){
            if(sql.isBlank())continue;
            String mapped=SqlNames.rebase(sql,engine,source.getName(),target.getName(),names);
            String tableName=names.getOrDefault(e.getName(),e.getName());
            if(e.getKind()==ObjectKind.TABLE&&(!source.getName().equals(target.getName())||!tableName.equals(e.getName())))mapped=SqlNames.renameStructureHeader(mapped,engine,structureNames(e,tableName,engine,Map.of()));SqlNames.requireCreateOnly(mapped);scripts.add(mapped);
        }
        return List.copyOf(scripts);
    }
}
