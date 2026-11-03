package com.tablebridge;

import com.intellij.database.Dbms;
import com.intellij.database.model.*;
import com.intellij.database.model.basic.*;
import com.intellij.database.script.generator.*;
import com.intellij.database.util.Version;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.ProjectManager;
import java.nio.file.*;
import java.sql.Types;
import java.util.*;

/** Only injected into the isolated smoke-test plugin, never shipped. */
public final class SdkSmoke {
    public SdkSmoke(){ApplicationManager.getApplication().getMessageBus().connect().subscribe(com.intellij.ide.AppLifecycleListener.TOPIC,new com.intellij.ide.AppLifecycleListener(){
        @Override public void appStarted(){ApplicationManager.getApplication().invokeLater(SdkSmoke::runSmoke);}
    });}
    private static void runSmoke(){
        if(Boolean.getBoolean("tablebridge.smoke.previewOnly")){
            Path output=Path.of(System.getProperty("tablebridge.smoke.output"));
            try{SyncPreviewSmoke.run(output);Files.writeString(output,"PASS production sync preview: counts, field samples, refresh availability, confirmation, unavailable fallback, frozen recovery snapshot.\n");}catch(Throwable failure){try{Files.writeString(output,"FAIL "+failure+"\nCAUSE "+failure.getCause()+"\n"+Arrays.toString(failure.getStackTrace()));}catch(Exception e){throw new RuntimeException(e);}}
            return;
        }
        StringBuilder result=new StringBuilder();
        try {
            Class.forName("com.intellij.database.remote.dbimport.ErrorRecord",true,RemoteBulk.class.getClassLoader());
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper",true,RowDigest.class.getClassLoader());
            javax.sql.rowset.RowSetProvider.newFactory().createCachedRowSet().close();
            result.append("PASS bulk transport dependencies available in plugin classloader\n");
            for(Dbms db:List.of(Dbms.MYSQL,Dbms.POSTGRES,Dbms.ORACLE,Dbms.MSSQL)){
                BasicModModel model=ModelFactory.BLACK_HOLE.createModel(db);
                BasicElement[] holder=new BasicElement[1];
                model.mod(root->{
                    root.setDbms(db);root.setServerVersion(Version.parse(db==Dbms.ORACLE?"19.0":db==Dbms.MYSQL?"8.0":"16.0"));
                    BasicModElement parent=root;
                    if(db==Dbms.POSTGRES||db==Dbms.MSSQL){BasicModNamedElement database=(BasicModNamedElement)root.familyOf(ObjectKind.DATABASE).createNewOne();database.setName("source_db");parent=database;}
                    BasicModNamedElement schema=(BasicModNamedElement)parent.familyOf(ObjectKind.SCHEMA).createNewOne();schema.setName("source_schema");
                    BasicModTable table=(BasicModTable)schema.familyOf(ObjectKind.TABLE).createNewOne();table.setName("items");var column=table.getColumns().createOrGet("id");column.setDataType(new DataType(null,"integer",null,false,Types.INTEGER));column.setPosition((short)1);holder[0]=table;
                });
                var task=new ScriptingSingleModelTaskBuilder(model,ScriptCategory.CREATE_COMPLETE);task.setElements(List.of(holder[0]));task.setCurrentDatabase(holder[0].getDatabase());task.setVersion(Version.parse(db==Dbms.ORACLE?"19.0":db==Dbms.MYSQL?"8.0":"16.0"));task.setOptions(new CustomScriptingOptions(Map.of(ScriptingOptionStatic.CONSIDER_SCHEMA_NAME,AutoNeverAlways.ALWAYS,ScriptingOptionStatic.USE_SEMICOLON,false,ScriptingOptionStatic.CREATE_OR_REPLACE,false)));
                var generated=ReadAction.compute(()->ScriptGenerators.INSTANCE.makeScript(ProjectManager.getInstance().getDefaultProject(),task.build()));
                result.append(db.getName()).append('\n');for(String sql:generated.getScriptStatementsTexts()){result.append(sql).append('\n');String mapped=SqlNames.rebase(sql,db==Dbms.MYSQL?Engine.MYSQL:db==Dbms.POSTGRES?Engine.POSTGRES:db==Dbms.ORACLE?Engine.ORACLE:Engine.MSSQL,"source_schema","target_schema",Map.of("items","items_copy"));if(!mapped.contains("target_schema")||!mapped.contains("items_copy"))throw new AssertionError("Missing destination qualification: "+mapped);result.append("MAPPED: ").append(mapped).append('\n');}
            }
            result.append("PASS native DDL generation for four engines\n");
            PlanSmoke.run(Path.of(System.getProperty("tablebridge.smoke.output")));
            result.append("PASS native model transfer plans\n");
            UiSmoke.run(Path.of(System.getProperty("tablebridge.smoke.output")));
            result.append("PASS production UI interactions and rendering\n");
        }catch(Throwable t){result.append("FAIL ").append(t).append('\n');Throwable cause=t.getCause();while(cause!=null){result.append("CAUSE ").append(cause).append('\n');cause=cause.getCause();}for(StackTraceElement e:t.getStackTrace())result.append(e).append('\n');}
        try{Files.writeString(Path.of(System.getProperty("tablebridge.smoke.output")),result);}catch(Exception e){throw new RuntimeException(e);}
    }
}
