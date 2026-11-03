package com.tablebridge;

import com.intellij.database.Dbms;
import com.intellij.openapi.project.ProjectManager;
import java.nio.file.*;
import java.sql.Types;
import java.util.*;
import java.lang.reflect.*;
import javax.swing.*;

/** Production confirmation page in an isolated IDE, without database connections or modal editors. */
final class SyncPreviewSmoke {
    static void run(Path output)throws Exception{
        var source=new PlanSmoke.Fixture(Dbms.POSTGRES,"18.3","source",false);
        var target=new PlanSmoke.Fixture(Dbms.MYSQL,"8.0","target",false);
        TransferDialog dialog=new TransferDialog(ProjectManager.getInstance().getDefaultProject(),List.of(source.table()),target.schema());
        try{
            var plan=new TransferPlan(source.schema(),target.schema(),Engine.POSTGRES,Engine.MYSQL,List.of(new TransferPlan.Item(source.table(),"items",true,List.of(),List.of(),List.of(new TransferPlan.Column("id",false,Types.INTEGER)),ExistingPolicy.MIRROR)),false,List.of());plan.recoverable=true;plan.checkpointKeys=List.of(List.of("id"));
            plan.syncPreview=List.of(new SyncPreview.Table(0,"items",120,35,8,0,RowDigest.empty(),RowDigest.empty(),List.of(Types.INTEGER),List.of(new SyncPreview.Sample(UiText.text("更新"),"id=42",List.of("note: \"old\" → \"中文 new\"")))));
            Method finish=TransferDialog.class.getDeclaredMethod("finishPrepare",TransferPlan.class,String.class);finish.setAccessible(true);finish.invoke(dialog,plan,null);
            JTextArea diff=(JTextArea)field(dialog,"diff");JButton refresh=(JButton)field(dialog,"previewDiff"),start=(JButton)field(dialog,"next");JCheckBox acknowledge=(JCheckBox)field(dialog,"acknowledge");
            PlanSmoke.check(diff.getText().contains("120")&&diff.getText().contains("35")&&diff.getText().contains("8")&&diff.getText().contains("note:"),"production diff shows counts and samples");
            PlanSmoke.check(refresh.isEnabled()&&!start.isEnabled(),"fresh preview requires confirmation and allows refresh");
            for(java.awt.Container parent=diff.getParent();parent!=null;parent=parent.getParent())if(parent instanceof JTabbedPane tabs){for(int i=0;i<tabs.getTabCount();i++)if(SwingUtilities.isDescendingFrom(diff,tabs.getComponentAt(i)))tabs.setSelectedIndex(i);break;}
            UiSmoke.capture((JComponent)dialog.getContentPane(),output.resolveSibling("ui-sync-preview.png"),820,620);
            acknowledge.doClick();PlanSmoke.check(start.isEnabled(),"review acknowledgement enables synchronization");
            plan.syncPreview=List.of();finish.invoke(dialog,plan,"Preview read limit reached");
            PlanSmoke.check(diff.getText().contains("Preview read limit reached")&&!start.isEnabled(),"unavailable preview explains fallback and resets confirmation");
            plan.resume=true;finish.invoke(dialog,plan,null);PlanSmoke.check(!refresh.isEnabled(),"recovery freezes snapshot and disables refresh");
            if(UiText.english())PlanSmoke.check(SyncPreview.text(List.of()).codePoints().noneMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN),"English preview explanation translated");
        }finally{dialog.close(0);}
    }
    private static Object field(Object object,String name)throws Exception{Field field=TransferDialog.class.getDeclaredField(name);field.setAccessible(true);return field.get(object);}
}
