package com.tablebridge;

import com.intellij.database.dataSource.*;
import com.intellij.database.psi.DbElement;
import com.intellij.database.run.ConsoleRunConfiguration;
import com.intellij.database.util.*;
import com.intellij.database.actions.RefreshActionsLogic;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

final class TransferRunner {
    private TransferRunner(){}
    static TransferControl start(Project project,TransferPlan plan,Consumer<String> uiLog,Consumer<TransferReport.Snapshot> updates,Consumer<TransferReport.Snapshot> done){
        if(plan.recoverable)return DurableRunner.start(project,plan,uiLog,updates,done);
        Consumer<String> log=line->{uiLog.accept(line);com.intellij.openapi.diagnostic.Logger.getInstance(TransferRunner.class).info(line);};
        TransferControl control=new TransferControl();
        new Task.Backgroundable(project,"TableBridge 数据传输",true){
            @Override public void run(ProgressIndicator indicator){
                control.attach(indicator);indicator.setIndeterminate(false);
                TransferReport report=new TransferReport(plan.items.stream().map(i->new TransferReport.ObjectInfo(i.source().getName(),i.targetName(),DbSelection.group(i.source()))).toList());
                Runnable publish=()->updates.accept(report.snapshot());
                List<Integer> committed=new ArrayList<>();boolean cancelled=false;String stop=null;
                long connectionStarted=System.nanoTime();
                log.accept("TableBridge：正在建立传输连接…");
                try(var sourceRef=plan.serverSide()?null:connect(project,plan.sourceNamespace);var targetRef=connect(project,plan.targetNamespace)){
                    log.accept("建立连接耗时："+elapsed(connectionStarted));
                    Connection target=control.wrap(RemoteJdbc.connection(targetRef.get().getRemoteConnection()));
                    Connection source=sourceRef==null?target:control.wrap(RemoteJdbc.connection(sourceRef.get().getRemoteConnection()));
                    try {
                        indicator.checkCanceled();
                        if(!plan.serverSide())configure(source,plan.sourceNamespace,plan.engine);configure(target,plan.targetNamespace,plan.targetEngine);
                        TargetValidation.distinct(source,target,plan);
                        source.setAutoCommit(false);target.setAutoCommit(false);
                        Map<Integer,TargetValidation.Shape> shapes=new HashMap<>();
                        // Validate every source and existing destination before DELETE or DROP can run.
                        log.accept("正在检查来源字段与目标结构…");publish.run();
                        for(int index=0;index<plan.items.size();index++){
                            indicator.checkCanceled();var item=plan.items.get(index);if(!item.data())continue;
                            List<String> names=item.columns().stream().map(TransferPlan.Column::name).toList();
                            try(Statement s=source.createStatement();ResultSet rs=s.executeQuery("SELECT "+String.join(",",names.stream().map(plan.engine::quote).toList())+" FROM "+qualified(plan,item,true)+" WHERE 1=0")){
                                if(!plan.serverSide())RowCopier.checkTypes(rs.getMetaData(),item.columns().stream().map(TransferPlan.Column::targetType).toList(),plan.engine,plan.targetEngine);
                                if(item.action()==ExistingPolicy.APPEND||item.action()==ExistingPolicy.CLEAR)shapes.put(index,TargetValidation.existing(target,plan.targetEngine,qualified(plan,item,false),names,rs.getMetaData()));
                            }
                            if(item.action()==ExistingPolicy.CLEAR)TargetValidation.clearAllowed(target,plan.targetEngine,DasUtil.getCatalog(plan.targetNamespace),plan.targetNamespace.getName(),item.targetName());
                        }
                        for(int index=0;index<plan.items.size();index++){
                            indicator.checkCanceled();var item=plan.items.get(index);indicator.setText(item.source().getName());indicator.setFraction((double)index/plan.items.size());
                            try {
                                report.stage(index,TransferReport.State.CREATING);publish.run();
                                log.accept("处理 "+item.targetName()+" · "+item.action());
                                long baseline=item.action()==ExistingPolicy.APPEND?TargetValidation.count(target,qualified(plan,item,false),plan.targetEngine):0;
                                long phaseStarted=System.nanoTime();
                                for(String sql:item.before()){indicator.checkCanceled();RowCopier.execute(target,sql);}
                                log.accept("结构处理耗时："+elapsed(phaseStarted));
                                if(item.data()){
                                    phaseStarted=System.nanoTime();
                                    report.stage(index,TransferReport.State.COPYING);publish.run();
                                    List<String> names=item.columns().stream().map(TransferPlan.Column::name).toList();
                                    TargetValidation.Shape shape=shapes.get(index);if(shape==null&&item.manualStructure())shape=TargetValidation.created(target,plan.targetEngine,qualified(plan,item,false),item.columns());boolean identity=shape==null?item.columns().stream().anyMatch(TransferPlan.Column::identity):shape.identity();
                                    List<Integer> types=shape==null?item.columns().stream().map(TransferPlan.Column::targetType).toList():shape.types();
                                    log.accept(plan.serverSide()?"数据库内部复制："+item.targetName():"跨连接分批复制："+item.targetName());
                                    RowDigest.Value original=plan.verifyContent&&item.action()==ExistingPolicy.APPEND?RowDigest.read(target,plan.targetEngine,qualified(plan,item,false),names,types,indicator::checkCanceled):RowDigest.empty();
                                    RowDigest.Value expected=plan.verifyContent?original.plus(RowDigest.read(source,plan.engine,qualified(plan,item,true),names,types,indicator::checkCanceled)):null;
                                    long rows=plan.serverSide()?ServerCopier.copy(target,plan.targetEngine,qualified(plan,item,true),qualified(plan,item,false),names,identity,indicator::isCanceled,baseline)
                                        :RowCopier.copy(source,target,plan.engine,plan.targetEngine,qualified(plan,item,true),qualified(plan,item,false),names,identity,types,indicator::checkCanceled,n->indicator.setText2("已处理 "+n+" 行"),log);
                                    log.accept("数据复制耗时："+elapsed(phaseStarted)+" · "+rows+" 行");phaseStarted=System.nanoTime();
                                    report.rows(index,rows);report.stage(index,TransferReport.State.VERIFYING);publish.run();
                                    if(!plan.serverSide()&&TargetValidation.count(target,qualified(plan,item,false),plan.targetEngine)!=Math.addExact(baseline,rows))throw new SQLException("目标行数校验失败");
                                    if(plan.verifyContent){log.accept("正在核对全部字段内容："+item.targetName());RowDigest.Value actual=RowDigest.read(target,plan.targetEngine,qualified(plan,item,false),names,types,indicator::checkCanceled);if(!actual.equals(expected))throw new SQLException("完整记录内容校验失败；本对象数据将回滚");log.accept("完整记录内容校验通过："+item.targetName());}
                                    reseedPostgres(target,plan,item);log.accept("记录校验通过："+item.targetName()+" · "+rows+" 行");
                                }
                                log.accept("校验耗时："+elapsed(phaseStarted));phaseStarted=System.nanoTime();
                                indicator.checkCanceled();target.commit();log.accept("提交耗时："+elapsed(phaseStarted));committed.add(index);report.committed(index,!item.after().isEmpty());publish.run();log.accept("已提交："+item.targetName());
                            }catch(ProcessCanceledException ex){rollback(target,log);throw ex;}
                            catch(Exception ex){if(control.cancelled()){rollback(target,log);throw new ProcessCanceledException();}report.fail(index,ex.getMessage());publish.run();if(!rollback(target,log)||(source!=target&&!rollback(source,log)))throw new SQLException("回滚未能确认，已停止后续对象",ex);log.accept("失败："+item.targetName()+" · "+ex.getMessage());if(!plan.continueOnError)throw ex;}
                        }
                        Set<Integer> postFailed=new HashSet<>();
                        // All referenced unique indexes must exist before any cross-table foreign key.
                        for(int phase=0;phase<3;phase++)for(int index:committed){var item=plan.items.get(index);if(item.after().isEmpty()||postFailed.contains(index))continue;
                            indicator.checkCanceled();report.stage(index,TransferReport.State.FINISHING);publish.run();
                            try{for(String sql:item.after()){if(CrossStructure.phase(sql)!=phase)continue;indicator.checkCanceled();RowCopier.execute(target,sql);}indicator.checkCanceled();target.commit();if(phase==2)report.success(index);publish.run();}
                            catch(ProcessCanceledException ex){rollback(target,log);throw ex;}
                            catch(Exception ex){if(control.cancelled()){rollback(target,log);throw new ProcessCanceledException();}report.fail(index,ex.getMessage());publish.run();if(!rollback(target,log))throw new SQLException("回滚未能确认，已停止后续对象",ex);postFailed.add(index);log.accept("后置结构失败："+item.targetName()+" · "+ex.getMessage());if(!plan.continueOnError)throw ex;}
                        }
                        indicator.setFraction(1);
                    }finally{rollback(target,log);if(source!=target)rollback(source,log);}
                }catch(ProcessCanceledException ex){cancelled=true;stop="已取消；已提交数据保留。MySQL/Oracle 可能保留已创建结构。";}
                catch(Exception ex){cancelled=control.cancelled();stop=(cancelled?"已取消：":"传输停止：")+ex.getMessage()+"。已提交对象不会自动删除。";}
                finally{
                    control.detach();report.finish(cancelled,stop);TransferReport.Snapshot result=report.snapshot();log.accept(result.summary());if(stop!=null)log.accept(stop);updates.accept(result);
                    ApplicationManager.getApplication().invokeLater(()->{try{if(!project.isDisposed())RefreshActionsLogic.runRegularRefresh(project,DbImplUtilCore.getLocalDataSource(plan.targetNamespace),List.of(DbSelection.basic(plan.targetNamespace)));}catch(Exception ex){log.accept("目标刷新失败，请手动刷新："+ex.getMessage());}finally{done.accept(result);}},ModalityState.any());
                }
            }
        }.queue();return control;
    }
    private static String elapsed(long started){return String.format(java.util.Locale.ROOT,"%.3f s",(System.nanoTime()-started)/1e9);}
    private static boolean rollback(Connection c,Consumer<String> log){try{c.rollback();return true;}catch(SQLException ex){log.accept("回滚未能确认："+ex.getMessage());return false;}}
    static GuardedRef<DatabaseConnection> connect(Project project,DbElement ns)throws SQLException{
        LocalDataSource ds=DbImplUtilCore.getLocalDataSource(ns);
        ConsoleRunConfiguration config=ConsoleRunConfiguration.newConfiguration(project);config.setOptionsFromDataSource(ds);
        return DatabaseConnectionManager.getInstance().build(project,ds).setRunConfiguration(config).setDestination(DbSelection.objectPath(ns)).setAskPassword(true).createBlocking();
    }
    static void configure(Connection c,DbElement namespace,Engine engine)throws SQLException{
        String catalog=DasUtil.getCatalog(namespace);
        switch(engine){
            case MYSQL -> {c.setCatalog(namespace.getName());RowCopier.execute(c,"SET SESSION sql_mode = CONCAT_WS(',', @@SESSION.sql_mode, 'NO_AUTO_VALUE_ON_ZERO', 'STRICT_ALL_TABLES')");RowCopier.execute(c,"SET SESSION foreign_key_checks = 1");}
            case MSSQL -> {if(catalog!=null)c.setCatalog(catalog);}
            case POSTGRES -> {if(catalog!=null&&!catalog.equals(c.getCatalog()))throw new SQLException("请使用连接到目标数据库 "+catalog+" 的 PostgreSQL 连接");RowCopier.execute(c,"SET search_path TO "+engine.quote(namespace.getName()));}
            case ORACLE -> {RowCopier.execute(c,"ALTER SESSION SET CURRENT_SCHEMA = "+engine.quote(namespace.getName()));SpecialValues.oracleSession(c);}
        }
    }
    private static String qualified(TransferPlan p,TransferPlan.Item item,boolean source){return (source?p.engine:p.targetEngine).qualified(source?p.sourceNamespace.getName():p.targetNamespace.getName(),source?item.source().getName():item.targetName());}
    static void reseedPostgres(Connection target,TransferPlan p,TransferPlan.Item item)throws SQLException{
        if(p.targetEngine!=Engine.POSTGRES)return;
        for(var col:item.columns())if(col.identity()){
            String table=qualified(p,item,false),sequence=null;
            try(PreparedStatement s=target.prepareStatement("SELECT pg_get_serial_sequence(?, ?)")){s.setString(1,table);s.setString(2,col.name());try(ResultSet rs=s.executeQuery()){if(rs.next())sequence=rs.getString(1);}}
            if(sequence==null&&item.action()!=ExistingPolicy.APPEND&&item.action()!=ExistingPolicy.CLEAR){
                for(var sourceColumn:DasUtil.getColumns(item.source()))if(sourceColumn.getName().equals(col.name())){var ref=PostgresSequences.reference(sourceColumn.getDefault(),p.sourceNamespace.getName());if(ref!=null)for(var dep:p.items)if(dep.source().getKind()==com.intellij.database.model.ObjectKind.SEQUENCE&&dep.source().getName().equals(ref.name()))sequence=p.targetEngine.qualified(p.targetNamespace.getName(),dep.targetName());}
            }
            if(sequence==null)continue;
            // Advance without moving a pre-existing sequence backwards; retain its increment direction.
            try(PreparedStatement info=target.prepareStatement("SELECT seqincrement FROM pg_sequence WHERE seqrelid=?::regclass")){info.setString(1,sequence);try(ResultSet rs=info.executeQuery()){if(!rs.next())continue;boolean ascending=rs.getLong(1)>0;
                try(Statement s=target.createStatement();ResultSet max=s.executeQuery("SELECT "+(ascending?"MAX":"MIN")+"("+p.targetEngine.quote(col.name())+") FROM "+table)){max.next();var value=max.getBigDecimal(1);if(value==null)continue;
                    try(PreparedStatement set=target.prepareStatement("SELECT setval(?::regclass, "+(ascending?"GREATEST":"LEAST")+"(?, pg_sequence_last_value(?::regclass)), true)")){set.setString(1,sequence);set.setBigDecimal(2,value);set.setString(3,sequence);set.execute();}
                }
            }}
        }
    }
}
