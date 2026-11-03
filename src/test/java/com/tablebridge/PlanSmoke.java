package com.tablebridge;

import com.intellij.database.Dbms;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.model.*;
import com.intellij.database.model.basic.*;
import com.intellij.database.psi.*;
import com.intellij.database.util.Version;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.ProjectManager;
import java.lang.reflect.*;
import java.sql.Types;
import java.math.BigInteger;
import java.util.*;
import java.nio.file.*;

/** Native model + PSI test adapter; no stored IDE data sources or connections. */
final class PlanSmoke {
    static void run(Path output)throws Exception{
        Fixture pg=new Fixture(Dbms.POSTGRES,"16.0","public",true);Fixture mysql=new Fixture(Dbms.MYSQL,"8.0","target",false);
        var project=ProjectManager.getInstance().getDefaultProject();
        var plan=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.table(),"items_copy",true)),pg.schema(),false,false));
        check(plan.items.size()==2,"serial dependency automatically included");
        check(plan.preview().contains("items_copy_id_seq"),"serial target sequence renamed");
        check(plan.preview().contains("nextval('\"public\".\"items_copy_id_seq\"'::regclass)"),"table uses independent sequence");
        var cross=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.table(),"from_pg",true)),mysql.schema(),false,false));
        check(cross.targetEngine==Engine.MYSQL&&cross.preview().contains("CREATE TABLE `target`.`from_pg`"),"native PG model maps to MySQL");
        check(cross.items.getFirst().after().stream().anyMatch(sql->sql.contains("CREATE UNIQUE INDEX `items_lookup`")),"native index metadata feeds cross-engine plan");
        check(pg.outerView instanceof BasicSourceAware,"view model implements source-aware");
        var sourceBody=(BasicSourceAware)pg.outerView;
        check(!sourceBody.isObfuscated(),"view model not obfuscated");
        check(sourceBody.getSourceText()!=null,"view source text retained after model modification");
        check(!pg.outerView.isOutdated(),"view model not outdated");
        var viewPlan=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.wrap(pg.outerView),"outer_copy",false),new PlanBuilder.Choice(pg.wrap(pg.innerView),"inner_copy",false),new PlanBuilder.Choice(pg.table(),"base_copy",false)),mysql.schema(),false,false));
        check(viewPlan.items.size()==3&&viewPlan.items.get(1).targetName().equals("inner_copy")&&viewPlan.items.get(2).targetName().equals("outer_copy"),"native view source model topological order");
        check(viewPlan.preview().contains("FROM `target`.`base_copy` `items`")&&viewPlan.preview().contains("FROM `target`.`inner_copy` `v_inner`"),"native view bodies target renamed dependencies");
        ReadAction.run(()->{try{
            viewPlan.recoverable=true;var file=JobManifest.save(output.getParent().resolve("jobs-test"),viewPlan);
            var restored=JobManifest.load(file,pg.schema(),mysql.schema(),List.of(pg.wrap(pg.outerView),pg.wrap(pg.innerView),pg.table()));
            check(restored.resume&&restored.recoverable&&restored.jobId.equals(viewPlan.jobId)&&restored.items.equals(viewPlan.items),"recovery plan preserves exact execution identity");
            check(JobManifest.fingerprint(restored).equals(JobManifest.fingerprint(viewPlan)),"recovery fingerprint round trip");java.nio.file.Files.delete(file);
        }catch(java.io.IOException ex){throw new RuntimeException(ex);}});
        boolean missingViewDependency=false;
        try{ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.wrap(pg.innerView),"only_view",false)),mysql.schema(),false,false));}catch(IllegalArgumentException expected){missingViewDependency=expected.getMessage().contains("补选视图依赖");}
        check(missingViewDependency,"native view missing dependency fails during planning");
        Fixture oracle=new Fixture(Dbms.ORACLE,"19.0","TARGET",false);
        var sequence=pg.model.getRoot().getChildren().flatMap(root->root.getChildren()).flatMap(ns->ns.getChildren()).filter(e->e.getKind()==ObjectKind.SEQUENCE).first();
        check(sequence!=null,"native sequence fixture loaded");
        var sequencePlan=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.wrap(sequence),"sequence_copy",false)),oracle.schema(),false,false));
        check(sequencePlan.preview().contains("CREATE SEQUENCE \"TARGET\".\"sequence_copy\""),"native sequence cross Oracle plan");
        var typePlan=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(oracle.wrap(oracle.objectType),"record_copy",false)),pg.schema(),false,false));
        check(typePlan.preview().contains("CREATE TYPE \"public\".\"record_copy\" AS (\"amount\" numeric(10,2))"),"native Oracle object attributes feed PG composite plan");
        var routinePlan=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.wrap(pg.function),"twice_copy",false)),mysql.schema(),false,false));
        check(routinePlan.preview().contains("NO SQL RETURN `a`*2"),"native scalar function metadata cross plan");
        BasicElement[] overloaded=new BasicElement[1];pg.model.mod(root->{
            var routine=(BasicModRoutine)((BasicModElement)pg.schema).familyOf(ObjectKind.ROUTINE).createNewOne();routine.setName("twice");routine.setRoutineKind(DasRoutine.Kind.FUNCTION);
            var arg=routine.getArguments().createOrGet("text_arg");arg.setArgumentDirection(ArgumentDirection.IN);arg.setDataType(new DataType(null,"text",0,0,LengthUnit.NONE,false,null,false,Types.VARCHAR));arg.setPosition((short)1);
            String body="CREATE FUNCTION public.twice(text_arg text) RETURNS text LANGUAGE sql AS $$SELECT text_arg$$";routine.setSourceText(new com.intellij.database.script.SimpleCompositeText(body,com.intellij.database.model.properties.CompositeText.Kind.ORIGINAL_TEXT));routine.setSourceTextLength(body.length());routine.setOutdated(false);overloaded[0]=routine;
        });
        check(!ObjectIdentity.signature(pg.wrap(overloaded[0])).equals(ObjectIdentity.signature(pg.wrap(pg.function))),"routine identities separate overloaded signatures");
        var aliases=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.wrap(pg.function),"twice_num",false,Map.of(),"CREATE FUNCTION `target`.`twice_num`(a decimal(10,2)) RETURNS decimal(10,2) DETERMINISTIC NO SQL RETURN a * 2",Set.of()),new PlanBuilder.Choice(pg.wrap(overloaded[0]),"twice_text",false,Map.of(),"CREATE FUNCTION `target`.`twice_text`(a text) RETURNS text DETERMINISTIC NO SQL RETURN a",Set.of())),mysql.schema(),false,false));
        check(aliases.items.size()==2,"reviewed overload aliases included independently");
        var manualPlan=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.wrap(pg.procedure),"manual_proc_copy",false,Map.of(),"CREATE PROCEDURE `target`.`manual_proc_copy`() BEGIN SELECT 1; END;",Set.of("items")),new PlanBuilder.Choice(pg.table(),"manual_base",false)),mysql.schema(),false,false));
        check(manualPlan.items.getFirst().source().equals(pg.table())&&manualPlan.preview().contains("CREATE PROCEDURE `target`.`manual_proc_copy`"),"manual procedure bound to destination and dependency ordered");
        var append=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.table(),"items",true)),mysql.schema(),false,false,ExistingPolicy.APPEND));
        check(append.items.getFirst().before().isEmpty()&&append.items.getFirst().action()==ExistingPolicy.APPEND,"append has no DDL");
        var clear=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.table(),"items",true)),mysql.schema(),false,false,ExistingPolicy.CLEAR));
        check(clear.items.getFirst().before().equals(List.of("DELETE FROM `target`.`items`")),"clear plan exact target");
        var replace=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.table(),"items",true)),mysql.schema(),false,false,ExistingPolicy.RECREATE));
        check(replace.items.getFirst().before().getFirst().equals("DROP TABLE `target`.`items`")&&replace.destructive(),"rebuild explicitly destructive");
        Fixture triggerSource=new Fixture(Dbms.MYSQL,"8.0","trigger_source",false);
        triggerSource.model.mod(root->{
            var trigger=(BasicModTrigger)((BasicModElement)triggerSource.table).familyOf(ObjectKind.TRIGGER).createNewOne();trigger.setName("audit");trigger.setEvents(Set.of(TrigEvent.INSERT));trigger.setTurn(TrigTurn.BEFORE_ROW);
            check(trigger instanceof BasicModSourceAware,"native MySQL trigger source-aware model");
            String body="SET NEW.id=NEW.id+10";
            var text=(BasicModSourceAware)trigger;text.setSourceText(new com.intellij.database.script.SimpleCompositeText(body,com.intellij.database.model.properties.CompositeText.Kind.ORIGINAL_TEXT));text.setSourceTextLength(body.length());trigger.setOutdated(false);
        });
        var triggerPlan=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(triggerSource.table(),"trigger_copy",true)),triggerSource.schema(),false,false));
        Files.writeString(output.resolveSibling("structure-native-preview.txt"),triggerPlan.preview());
        check(triggerPlan.items.getFirst().after().stream().anyMatch(CrossStructure::isTrigger),"native triggers included after table and data");
        check(triggerPlan.preview().contains("trigger_copy_audit_seq"),"native schema-scoped MySQL trigger independently renamed");
        String editableStructure=ReadAction.compute(()->PlanBuilder.structurePreview(project,new PlanBuilder.Choice(triggerSource.table(),"review_copy",false),triggerSource.schema(),false,Map.of("items","review_copy")));
        check(TableStructureSql.validate(Engine.MYSQL,"trigger_source","review_copy",editableStructure).after().stream().anyMatch(CrossStructure::isTrigger),"production native preview round-trips full trigger DDL");
        boolean crossTriggerBlocked=false;try{ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(triggerSource.table(),"cross_trigger",true)),pg.schema(),false,false));}catch(IllegalArgumentException expected){crossTriggerBlocked=expected.getMessage().contains("触发器");}check(crossTriggerBlocked,"cross trigger never silently omitted");
        Fixture functional=new Fixture(Dbms.MYSQL,"8.0","func_source",false);
        functional.model.mod(root->{var ix=(BasicModIndex)((BasicTable)functional.table).getIndices().iterator().next();ix.setFunctionBased(true);ix.setColNames("\u001bid + 1");ix.setUnique(false);});
        var functionalPlan=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(functional.table(),"functional_copy",false)),pg.schema(),false,false));
        check(functionalPlan.preview().contains("((\"id\" + 1))"),"native expression metadata automatically feeds portable functional index");
        functional.model.mod(root->{var ix=(com.intellij.database.dialects.mysqlbase.model.MysqlBaseIndex)((BasicTable)functional.table).getIndices().iterator().next();ix.setFunctionBased(false);ix.setColNames("id");ix.setPrefixLengths(12);});
        boolean prefixBlocked=false;try{ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(functional.table(),"prefix_copy",false)),pg.schema(),false,false));}catch(IllegalArgumentException expected){prefixBlocked=expected.getMessage().contains("索引");}check(prefixBlocked,"native prefix metadata cannot silently become whole-column index");
        var reviewed=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.table(),"reviewed",true,Map.of(),"CREATE TABLE `target`.`reviewed`(id bigint)\n"+TableStructureSql.SEPARATOR+"\nCREATE INDEX ix ON `target`.`reviewed`(id)\n"+TableStructureSql.SEPARATOR+"\nCREATE TRIGGER `target`.`audit` BEFORE INSERT ON `target`.`reviewed` FOR EACH ROW SET NEW.id=NEW.id+1",Set.of())),mysql.schema(),false,false));
        check(reviewed.items.getFirst().before().size()==1&&reviewed.items.getFirst().after().size()==2&&reviewed.items.getFirst().data(),"manual full table structure feeds production data and post DDL plan");
        check(plan.preview().contains("items_copy_items_lookup_seq"),"same schema native index gets independent destination name");
        TransferDialog dialog=new TransferDialog(project,List.of(pg.table()),mysql.schema());
        try{
            UiSmoke.capture((javax.swing.JComponent)dialog.getContentPane(),output.resolveSibling("ui-wizard-connections.png"),820,620);
            var show=TransferDialog.class.getDeclaredMethod("showStep",int.class);show.setAccessible(true);show.invoke(dialog,1);
            UiSmoke.capture((javax.swing.JComponent)dialog.getContentPane(),output.resolveSibling("ui-wizard-objects.png"),820,620);
            var rowsField=TransferDialog.class.getDeclaredField("rows");rowsField.setAccessible(true);
            var dialogRows=(java.util.List<?>)rowsField.get(dialog);ObjectSelectionPanel.Item editable=(ObjectSelectionPanel.Item)dialogRows.stream().filter(r->((ObjectSelectionPanel.Item)r).originalName.equals("twice")).findFirst().orElseThrow();
            editable.custom=true;editable.name="twice_edited";
            Throwable[] editorFailure={null};boolean[] editorCaptured={false};
            javax.swing.Timer timer=new javax.swing.Timer(400,e->{for(java.awt.Window window:java.awt.Window.getWindows())if(window instanceof javax.swing.JDialog modal&&modal.isVisible()&&modal.getTitle().equals(UiText.text("转换定义 · ")+"twice")){
                try{UiSmoke.capture((javax.swing.JComponent)modal.getContentPane(),output.resolveSibling("ui-object-conversion.png"),880,600);editorCaptured[0]=true;}catch(Throwable error){editorFailure[0]=error;}finally{modal.dispose();}
            }});timer.setRepeats(false);timer.start();
            var edit=TransferDialog.class.getDeclaredMethod("editObjectSql",ObjectSelectionPanel.Item.class);edit.setAccessible(true);edit.invoke(dialog,editable);
            check(editorFailure[0]==null&&editorCaptured[0],"production non-table conversion editor renders source target and dependencies");
            ObjectSelectionPanel.Item tableEditable=(ObjectSelectionPanel.Item)dialogRows.stream().filter(r->((ObjectSelectionPanel.Item)r).originalName.equals("items")).findFirst().orElseThrow();tableEditable.custom=true;tableEditable.name="structure_review";
            boolean[] structureCaptured={false};javax.swing.Timer structureTimer=new javax.swing.Timer(400,e->{for(java.awt.Window window:java.awt.Window.getWindows())if(window instanceof javax.swing.JDialog modal&&modal.isVisible()&&modal.getTitle().equals(UiText.text("完整结构 · ")+"items")){try{UiSmoke.capture((javax.swing.JComponent)modal.getContentPane(),output.resolveSibling("ui-full-structure.png"),880,600);structureCaptured[0]=true;}catch(Throwable error){editorFailure[0]=error;}finally{modal.dispose();}}});structureTimer.setRepeats(false);structureTimer.start();edit.invoke(dialog,tableEditable);check(editorFailure[0]==null&&structureCaptured[0],"production table structure editor renders source and editable target");
            tableEditable.name="items_copy";var objectsField=TransferDialog.class.getDeclaredField("objects");objectsField.setAccessible(true);((ObjectSelectionPanel)objectsField.get(dialog)).refreshChoices();
            UiSmoke.capture((javax.swing.JComponent)dialog.getContentPane(),output.resolveSibling("ui-wizard-advanced.png"),820,620);
            var finish=TransferDialog.class.getDeclaredMethod("finishPrepare",TransferPlan.class,String.class);finish.setAccessible(true);finish.invoke(dialog,null,"测试类型检查失败");
            var statusField=TransferDialog.class.getDeclaredField("status");statusField.setAccessible(true);check(((javax.swing.JLabel)statusField.get(dialog)).getText().equals(UiText.text("无法继续：")+"测试类型检查失败"),"validation failure displayed explicitly");
            var demonstration=ReadAction.compute(()->PlanBuilder.build(project,List.of(new PlanBuilder.Choice(pg.table(),"items_copy",true)),mysql.schema(),false,false));
            if(UiText.english())check(demonstration.preview().codePoints().noneMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN),"ASCII sample SQL preview has no untranslated Chinese annotations");
            finish.invoke(dialog,demonstration,null);
            UiSmoke.capture((javax.swing.JComponent)dialog.getContentPane(),output.resolveSibling("ui-wizard-review.png"),820,620);
            var sqlTabsField=TransferDialog.class.getDeclaredField("sql");sqlTabsField.setAccessible(true);java.awt.Component sqlEditor=(java.awt.Component)sqlTabsField.get(dialog);for(java.awt.Container parent=sqlEditor.getParent();parent!=null;parent=parent.getParent())if(parent instanceof javax.swing.JTabbedPane tabs){for(int tab=0;tab<tabs.getTabCount();tab++)if(javax.swing.SwingUtilities.isDescendingFrom(sqlEditor,tabs.getComponentAt(tab))){tabs.setSelectedIndex(tab);break;}break;}
            UiSmoke.capture((javax.swing.JComponent)dialog.getContentPane(),output.resolveSibling("ui-wizard-sql.png"),820,620);
            var button=TransferDialog.class.getDeclaredField("next");button.setAccessible(true);check(!((javax.swing.JButton)button.get(dialog)).isEnabled(),"cross-engine plan requires UI acknowledgement");
            var ack=TransferDialog.class.getDeclaredField("acknowledge");ack.setAccessible(true);((javax.swing.JCheckBox)ack.get(dialog)).doClick();check(((javax.swing.JButton)button.get(dialog)).isEnabled(),"acknowledgement enables start");
        }finally{dialog.close(0);}
        Files.writeString(output.resolveSibling("plan-result.txt"),"PASS native-model plans: serial dependency/rename, PG-to-MySQL DDL, view source text/dependency order, append, clear, rebuild; native trigger DDL and independent names; automatic expression indexes/prefix guard; reviewed full structure and production editor.\n\n"+plan.preview()+"\n"+cross.preview());
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static final class MemorySources implements ModelTextStorage {
        final Map<BasicSourceAware,com.intellij.database.model.properties.CompositeText> text=new IdentityHashMap<>();
        final Map<BasicElement,Long> versions=new IdentityHashMap<>();
        public void save(BasicSourceAware source,com.intellij.database.model.properties.CompositeText value){text.put(source,value);}
        public com.intellij.database.model.properties.CompositeText load(BasicSourceAware source){return text.get(source);}
        public void queueDelete(BasicElement source){text.remove(source);versions.remove(source);}
        public Long getVersion(BasicElement element){return versions.get(element);}
        public void setVersion(BasicElement element,Long version){versions.put(element,version);}
        public void writeSession(BasicModModel model,Runnable action){action.run();}
        public void flushQueues(){}
        public void handleRename(BasicElement element,com.intellij.database.util.ObjectNamePart name){}
        public void clear(){text.clear();versions.clear();}
    }
    static final class Fixture {
        final BasicModModel model;final Dbms dbms;final Version version;final LocalDataSource local=new LocalDataSource();
        BasicElement schema,table,innerView,outerView,function,procedure,objectType;final Map<BasicElement,DbElement> wrapped=new IdentityHashMap<>();final DbDataSource dataSource;
        Fixture(Dbms dbms,String version,String schemaName,boolean serial){this.dbms=dbms;this.version=Version.parse(version);model=new ModelFactory(new MemorySources()).createModel(dbms);
            model.mod(root->{root.setDbms(dbms);root.setServerVersion(this.version);BasicModElement parent=root;if(dbms==Dbms.POSTGRES){var db=(BasicModNamedElement)root.familyOf(ObjectKind.DATABASE).createNewOne();db.setName("test_db");parent=db;}
                var ns=(BasicModNamedElement)parent.familyOf(ObjectKind.SCHEMA).createNewOne();ns.setName(schemaName);schema=ns;
                if(serial){var seq=(BasicModSequence)ns.familyOf(ObjectKind.SEQUENCE).createNewOne();seq.setName("items_id_seq");seq.setStartValue(BigInteger.ONE);seq.setSequenceIdentity(SequenceIdentity.of(1L,1L,1L,Long.MAX_VALUE));}
                var t=(BasicModTable)ns.familyOf(ObjectKind.TABLE).createNewOne();t.setName("items");table=t;var c=t.getColumns().createOrGet("id");c.setDataType(new DataType(null,"bigint",null,false,Types.OTHER));c.setNotNull(true);c.setPosition((short)1);if(serial)c.setDefaultExpression("nextval('public.items_id_seq'::regclass)");
                var index=(BasicModIndex)t.getIndices().createNewOne();((BasicModNamedElement)index).setName("items_lookup");index.setUnique(true);index.setColNames("id");
                var inner=(BasicModView)ns.familyOf(ObjectKind.VIEW).createNewOne();inner.setName("v_inner");innerView=inner;
                String innerSql="CREATE VIEW "+schemaName+".v_inner AS SELECT items.id FROM "+schemaName+".items WHERE items.id >= 10;";
                inner.setSourceText(new com.intellij.database.script.SimpleCompositeText(innerSql,com.intellij.database.model.properties.CompositeText.Kind.ORIGINAL_TEXT));inner.setSourceTextLength(innerSql.length());inner.setOutdated(false);inner.setObfuscated(false);
                var outer=(BasicModView)ns.familyOf(ObjectKind.VIEW).createNewOne();outer.setName("v_outer");outerView=outer;
                String outerSql="SELECT v_inner.id FROM "+schemaName+".v_inner";
                outer.setSourceText(new com.intellij.database.script.SimpleCompositeText(outerSql,com.intellij.database.model.properties.CompositeText.Kind.ORIGINAL_TEXT));outer.setSourceTextLength(outerSql.length());outer.setOutdated(false);outer.setObfuscated(false);
                var functionModel=(BasicModRoutine)ns.familyOf(ObjectKind.ROUTINE).createNewOne();functionModel.setName("twice");functionModel.setRoutineKind(DasRoutine.Kind.FUNCTION);function=functionModel;
                var parameter=functionModel.getArguments().createOrGet("a");parameter.setArgumentDirection(ArgumentDirection.IN);parameter.setDataType(new DataType(null,"numeric",10,2,LengthUnit.NONE,false,null,false,Types.NUMERIC));parameter.setPosition((short)1);
                var result=functionModel.getArguments().createOrGet("return");result.setArgumentDirection(ArgumentDirection.RETURN);result.setDataType(new DataType(null,"numeric",10,2,LengthUnit.NONE,false,null,false,Types.NUMERIC));
                String functionSql="CREATE FUNCTION "+schemaName+".twice(a numeric) RETURNS numeric LANGUAGE sql AS $$SELECT a * 2$$";
                functionModel.setSourceText(new com.intellij.database.script.SimpleCompositeText(functionSql,com.intellij.database.model.properties.CompositeText.Kind.ORIGINAL_TEXT));functionModel.setSourceTextLength(functionSql.length());functionModel.setOutdated(false);functionModel.setObfuscated(false);
                var procedureModel=(BasicModRoutine)ns.familyOf(ObjectKind.ROUTINE).createNewOne();procedureModel.setName("manual_proc");procedureModel.setRoutineKind(DasRoutine.Kind.PROCEDURE);procedure=procedureModel;
                if(dbms==Dbms.ORACLE){
                    var record=(BasicModNamedElement)ns.familyOf(ObjectKind.OBJECT_TYPE).createNewOne();record.setName("money_record");objectType=record;
                    var attribute=(BasicModTypedElement)record.familyOf(ObjectKind.OBJECT_ATTRIBUTE).createNewOne();((BasicModNamedElement)attribute).setName("amount");attribute.setDataType(new DataType(null,"number",10,2,LengthUnit.NONE,false,null,false,Types.NUMERIC));
                }
            });local.setModel(model);local.setReadOnly(false);
            dataSource=(DbDataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DbDataSource.class},(p,m,a)->switch(m.getName()){
                case "getDbms"->dbms;case "getVersion"->this.version;case "getName"->dbms.getName();case "getModel"->model;case "getDelegate","getDelegateDataSource"->local;case "getDataSource"->p;case "findElement"->a[0] instanceof BasicElement b?wrap(b):null;case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);case "isValid"->true;case "toString"->dbms.getName();default->null;
            });
        }
        DbElement schema(){return wrap(schema);}DbElement table(){return wrap(table);}
        DbElement wrap(BasicElement element){if(element==null)return dataSource;return wrapped.computeIfAbsent(element,b->(DbElement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DbElement.class},(p,m,a)->{
            switch(m.getName()){case "getDelegate":return b;case "getDataSource":return dataSource;case "getParent":case "getDasParent":return b.getParent()==null?dataSource:wrap(b.getParent());case "isValid":return true;case "equals":return p==a[0];case "hashCode":return System.identityHashCode(p);case "getName":case "toString":return b.getName();}
            try{var method=b.getClass().getMethod(m.getName(),m.getParameterTypes());method.setAccessible(true);return method.invoke(b,a);}catch(NoSuchMethodException ex){if(m.getReturnType()==boolean.class)return false;if(m.getReturnType()==int.class)return 0;return null;}catch(InvocationTargetException ex){throw ex.getCause();}
        }));}
    }
}
