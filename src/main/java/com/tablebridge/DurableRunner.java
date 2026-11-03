package com.tablebridge;

import com.intellij.openapi.application.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

/** Uses durable checkpoints; never retries an unconfirmed structure statement. */
final class DurableRunner {
    static TransferControl start(Project project,TransferPlan plan,Consumer<String> log,Consumer<TransferReport.Snapshot> updates,Consumer<TransferReport.Snapshot> done){
        TransferControl control=new TransferControl();
        new Task.Backgroundable(project,"TableBridge 可恢复传输",true){
            public void run(ProgressIndicator indicator){
                control.attach(indicator);TransferReport report=new TransferReport(plan.items.stream().map(i->new TransferReport.ObjectInfo(i.source().getName(),i.targetName(),DbSelection.group(i.source()))).toList());String stop=null;
                try{
                    JobManifest.freezeKeys(plan);var manifest=JobManifest.save(JobManifest.directory(),plan);log.accept("恢复计划："+manifest);
                    for(int attempt=0;;attempt++){
                        try{execute(project,plan,control,indicator,report,updates,log);break;}
                        catch(SQLException failure){if(control.cancelled()||attempt>=2||!plan.resume||!transientFailure(failure))throw failure;log.accept("连接/事务中断，正在核对持久检查点后重试（"+(attempt+1)+"/2）");indicator.checkCanceled();}
                    }
                }catch(Exception failure){stop=(control.cancelled()?"已取消：":"传输停止：")+failure.getMessage()+"；保留恢复计划和任务暂存，请通过恢复入口继续。";log.accept(stop);}
                finally{control.detach();report.finish(control.cancelled(),stop);var result=report.snapshot();updates.accept(result);ApplicationManager.getApplication().invokeLater(()->done.accept(result),ModalityState.any());}
            }
        }.queue();return control;
    }
    static void cleanup(Project project,TransferPlan plan,Consumer<String> done){
        new Task.Backgroundable(project,"TableBridge 清理任务暂存",false){public void run(ProgressIndicator indicator){String message;try(var ref=TransferRunner.connect(project,plan.targetNamespace)){
            Connection target=RemoteJdbc.connection(ref.get().getRemoteConnection());TransferRunner.configure(target,plan.targetNamespace,plan.targetEngine);target.setAutoCommit(false);
            ResumableTable job=new ResumableTable(target,plan.targetEngine,plan.targetNamespace.getName(),plan.jobId,true,JobManifest.fingerprint(plan),indicator::checkCanceled,line->{});
            List<Integer> items=new ArrayList<>();for(int i=0;i<plan.items.size();i++)if(plan.items.get(i).data())items.add(i);job.cleanup(items);java.nio.file.Files.deleteIfExists(JobManifest.directory().resolve(plan.jobId+".xml"));message="任务暂存与恢复计划已清理；目标对象保留。";
        }catch(Exception e){message="清理未完成："+e.getMessage();}String result=message;ApplicationManager.getApplication().invokeLater(()->done.accept(result),ModalityState.any());}}.queue();
    }
    static boolean transientFailure(SQLException error){for(SQLException e=error;e!=null;e=e.getNextException()){String state=e.getSQLState();if(e instanceof SQLRecoverableException||e instanceof SQLTransientConnectionException||(state!=null&&(state.startsWith("08")||state.equals("40001")||state.equals("40P01"))))return true;}return false;}
    private static void execute(Project project,TransferPlan plan,TransferControl control,ProgressIndicator indicator,TransferReport report,Consumer<TransferReport.Snapshot> updates,Consumer<String> log)throws Exception{
        try(var sourceRef=TransferRunner.connect(project,plan.sourceNamespace);var targetRef=TransferRunner.connect(project,plan.targetNamespace)){
            if(sourceRef.get()==targetRef.get()||sourceRef.get().getRemoteConnection().equals(targetRef.get().getRemoteConnection()))throw new SQLException("断点任务需要独立的来源和目标会话，请重新建立连接后再试");
            Connection source=control.wrap(RemoteJdbc.connection(sourceRef.get().getRemoteConnection())),target=control.wrap(RemoteJdbc.connection(targetRef.get().getRemoteConnection()));
            try{
                TransferRunner.configure(source,plan.sourceNamespace,plan.engine);TransferRunner.configure(target,plan.targetNamespace,plan.targetEngine);TargetValidation.distinct(source,target,plan);
                source.setTransactionIsolation(plan.engine==Engine.ORACLE?Connection.TRANSACTION_SERIALIZABLE:Connection.TRANSACTION_REPEATABLE_READ);source.setAutoCommit(false);target.setAutoCommit(false);
                if(!plan.resume){SyncPreview.verifySource(source,plan,indicator::checkCanceled);SyncPreview.verifyTarget(target,plan,null,indicator::checkCanceled);}
                ResumableTable job=new ResumableTable(target,plan.targetEngine,plan.targetNamespace.getName(),plan.jobId,plan.resume,JobManifest.fingerprint(plan),indicator::checkCanceled,log);plan.resume=true;
                Map<Integer,ResumableTable.Spec> specs=new LinkedHashMap<>();
                for(int i=0;i<plan.items.size();i++){
                    indicator.checkCanceled();var item=plan.items.get(i);report.stage(i,TransferReport.State.CREATING);updates.accept(report.snapshot());
                    List<String> ddl=item.action()==ExistingPolicy.CLEAR?List.of():item.before();int completed=job.structureProgress(i,ddl.size());
                    for(int step=completed;step<ddl.size();step++){String sql=ddl.get(step);String hash=ResumableTable.fingerprint(sql);job.structureStarting(i,step,hash);RowCopier.execute(target,sql);job.structureDone(i,step+1,hash);}
                    if(!item.data())continue;
                    String from=plan.engine.qualified(plan.sourceNamespace.getName(),item.source().getName()),to=plan.targetEngine.qualified(plan.targetNamespace.getName(),item.targetName());
                    var shape=TargetValidation.created(target,plan.targetEngine,to,item.columns());List<String> fields=item.columns().stream().map(TransferPlan.Column::name).toList();
                    if(item.action()==ExistingPolicy.CLEAR)TargetValidation.clearAllowed(target,plan.targetEngine,null,plan.targetNamespace.getName(),item.targetName());
                    requirePrimaryKey(target,plan.targetEngine,plan.targetNamespace.getName(),item.targetName(),JobManifest.keys(plan,i));
                    if(plan.targetEngine==Engine.MYSQL)requireTransactional(target,plan.targetNamespace.getName(),item.targetName());
                    var spec=new ResumableTable.Spec(from,to,fields,JobManifest.keys(plan,i),shape.types(),shape.identity(),item.action());specs.put(i,spec);report.stage(i,TransferReport.State.COPYING);
                    final int index=i;long rows=job.load(i,source,plan.engine,spec,n->{report.rows(index,n);indicator.setText2("已暂存 "+n+" 行");updates.accept(report.snapshot());});report.rows(i,rows);
                }
                // The target commit and APPLIED markers are one atomic transaction.
                List<String> tables=new ArrayList<>();for(int i=0;i<plan.items.size();i++)tables.add(specs.containsKey(i)?plan.items.get(i).targetName():null);
                List<Integer> order=SyncDependencies.order(target,plan.targetEngine,plan.targetNamespace.getName(),tables);
                SyncPreview.verifyTarget(target,plan,job,indicator::checkCanceled);
                Set<Integer> applied=new HashSet<>();for(int i:order)if(job.get(i).phase().equals("APPLIED"))applied.add(i);
                for(int i:order){report.stage(i,TransferReport.State.VERIFYING);job.apply(i,specs.get(i),false);TransferRunner.reseedPostgres(target,plan,plan.items.get(i));}
                var reverse=new ArrayList<>(order);Collections.reverse(reverse);for(int i:reverse)if(!applied.contains(i)&&specs.get(i).policy()==ExistingPolicy.MIRROR)job.deleteMissing(i,specs.get(i));
                for(int i:order)if(!applied.contains(i))job.verify(i,specs.get(i));
                indicator.checkCanceled();target.commit();for(int i=0;i<plan.items.size();i++){report.committed(i,!plan.items.get(i).after().isEmpty());updates.accept(report.snapshot());}
                int post=0;for(int phase=0;phase<3;phase++)for(int i=0;i<plan.items.size();i++)for(String sql:plan.items.get(i).after())if(CrossStructure.phase(sql)==phase){int id=post++;if(job.postCompleted(id))continue;report.stage(i,TransferReport.State.FINISHING);job.postStarting(id,sql);RowCopier.execute(target,sql);job.postDone(id,sql);}
                for(int i=0;i<plan.items.size();i++)report.success(i);report.message("恢复计划与目标检查点已保留；再次恢复不会重放已确认的数据/结构。");
            }finally{try{target.rollback();}finally{source.rollback();}}
        }
    }
    static void requirePrimaryKey(Connection c,Engine engine,String schema,String table,List<String> expected)throws SQLException{
        TreeMap<Integer,String> columns=new TreeMap<>();try(ResultSet rs=c.getMetaData().getPrimaryKeys(engine==Engine.MYSQL?schema:c.getCatalog(),engine==Engine.MYSQL?null:schema,table)){while(rs.next())columns.put(rs.getInt("KEY_SEQ"),rs.getString("COLUMN_NAME"));}if(!new ArrayList<>(columns.values()).equals(expected))throw new SQLException("恢复/同步要求目标主键与来源一致："+table);
    }
    static void requireTransactional(Connection c,String schema,String table)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT ENGINE FROM information_schema.tables WHERE table_schema=? AND table_name=?")){s.setString(1,schema);s.setString(2,table);try(ResultSet rs=s.executeQuery()){if(!rs.next()||!"InnoDB".equalsIgnoreCase(rs.getString(1)))throw new SQLException("恢复/同步要求目标使用 InnoDB："+table);}}}
}
