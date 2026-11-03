package com.tablebridge;

import com.intellij.database.psi.DbElement;
import java.util.*;

final class TransferPlan {
    record Item(DbElement source, String targetName, boolean data, List<String> before, List<String> after, List<Column> columns, ExistingPolicy action,boolean manualStructure) {
        Item(DbElement source,String targetName,boolean data,List<String> before,List<String> after,List<Column> columns,ExistingPolicy action){this(source,targetName,data,before,after,columns,action,false);}
        Item(DbElement source,String targetName,boolean data,List<String> before,List<String> after,List<Column> columns){this(source,targetName,data,before,after,columns,ExistingPolicy.FAIL);}
    }
    record Column(String name, boolean identity,int targetType) {Column(String name,boolean identity){this(name,identity,java.sql.Types.NULL);}}
    final DbElement sourceNamespace,targetNamespace;
    final Engine engine,targetEngine;
    boolean recoverable,resume;String jobId=UUID.randomUUID().toString();
    List<List<String>> checkpointKeys;
    final List<String> warnings;
    final List<Item> items;
    final boolean continueOnError;
    boolean verifyContent;
    List<SyncPreview.Table> syncPreview=List.of();
    TransferPlan(DbElement source,DbElement target,Engine engine,List<Item> items,boolean continueOnError){
        this(source,target,engine,DbSelection.engine(target),items,continueOnError,List.of());
    }
    TransferPlan(DbElement source,DbElement target,Engine engine,Engine targetEngine,List<Item> items,boolean continueOnError,List<String> warnings){
        this.targetEngine=targetEngine;this.warnings=List.copyOf(warnings);
        this.sourceNamespace=source;this.targetNamespace=target;this.engine=engine;this.items=List.copyOf(items);this.continueOnError=continueOnError;
    }
    boolean serverSide(){
        return !recoverable&&engine==targetEngine && sourceNamespace.getDataSource().equals(targetNamespace.getDataSource())
            && (engine==Engine.MYSQL || engine==Engine.ORACLE
                || (com.intellij.database.util.DasUtil.getCatalog(sourceNamespace)!=null
                    && Objects.equals(com.intellij.database.util.DasUtil.getCatalog(sourceNamespace),
                                      com.intellij.database.util.DasUtil.getCatalog(targetNamespace))));
    }
    boolean destructive(){return items.stream().anyMatch(i->i.action.destructive());}
    String targetTable(Item i){return targetEngine.qualified(targetNamespace.getName(),i.targetName);}
    String preview(){
        StringBuilder s=new StringBuilder(UiText.text("-- 阶段一：处理对象并复制数据\n\n"));if(recoverable)s.append(UiText.text("-- 断点任务：目标库中创建专属暂存/检查点表；来源快照摘要校验后按主键分页。恢复不重复已提交批次。\n"));warnings.forEach(w->s.append(UiText.text("-- 注意：")).append(UiText.text(w)).append("\n"));
        if(verifyContent)s.append(UiText.text("-- 全量内容校验：逐行读取并核对字段摘要，会额外读取来源和目标数据。\n"));
        for(Item i:items){
            s.append("-- ").append(i.source.getName()).append(" → ").append(i.targetName).append('\n');
            s.append(UiText.text("-- 目标处理：")).append(i.action==ExistingPolicy.FAIL?UiText.text("新建对象"):UiText.text(i.action.label)).append("\n");
            i.before.forEach(x->s.append(x).append("\n\n"));
            if(i.data){
                if(recoverable&&(i.action==ExistingPolicy.UPSERT||i.action==ExistingPolicy.MIRROR)){
                    int index=items.indexOf(i);String stage=targetEngine.qualified(targetNamespace.getName(),"tb_s_"+jobId.replace("-","").substring(0,16)+"_"+Integer.toUnsignedString(index,36));
                    SyncSql.merge(targetEngine,targetTable(i),stage,i.columns.stream().map(Column::name).toList(),JobManifest.keys(this,index),i.columns.stream().map(Column::targetType).toList(),i.columns.stream().anyMatch(Column::identity)).forEach(x->s.append(x).append(";\n"));
                    if(i.action==ExistingPolicy.MIRROR)s.append(SyncSql.deleteMissing(targetEngine,targetTable(i),stage,JobManifest.keys(this,index))).append(";\n");
                }
                if(serverSide())s.append(ServerCopier.sql(engine,engine.qualified(sourceNamespace.getName(),i.source.getName()),
                    engine.qualified(targetNamespace.getName(),i.targetName),i.columns.stream().map(Column::name).toList(),
                    i.columns.stream().anyMatch(Column::identity))).append(";\n");
                s.append(serverSide()?UiText.text("-- 数据库内部复制，校验并提交\n\n"):UiText.text("-- 分批复制记录（参数化写入），校验并提交\n\n"));
            }
        }
        for(int phase=0;phase<3;phase++){s.append("-- ").append(switch(phase){case 0->UiText.text("阶段二：全部记录复制后，创建索引、检查约束和注释");case 1->UiText.text("阶段三：创建外键");default->UiText.text("阶段四：最后创建触发器");}).append("\n\n");for(Item i:items)for(String sql:i.after)if(CrossStructure.phase(sql)==phase)s.append(sql).append("\n\n");}
        return s.toString();
    }
}
