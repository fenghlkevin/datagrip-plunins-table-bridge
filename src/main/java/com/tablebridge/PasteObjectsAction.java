package com.tablebridge;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
public final class PasteObjectsAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(AnActionEvent e){if(UiText.english()){e.getPresentation().setText("Paste database objects…");e.getPresentation().setDescription("Open the database object transfer wizard");}e.getPresentation().setEnabledAndVisible(DbSelection.pasteTarget(e.getDataContext())!=null&&!ObjectClipboard.read(e.getProject()).isEmpty());}
    @Override public void actionPerformed(AnActionEvent e){
        try{var source=ObjectClipboard.read(e.getProject());var target=DbSelection.pasteTarget(e.getDataContext());if(source.isEmpty()||target==null)return;
            if(!CrossEngineDdl.supports(DbSelection.engine(source.getFirst()),DbSelection.engine(target)))throw new IllegalArgumentException("跨类型首版仅支持 MySQL ↔ PostgreSQL");
            new TransferDialog(e.getProject(),source,target).show();
        }catch(IllegalArgumentException ex){Messages.showErrorDialog(e.getProject(),ex.getMessage(),"粘贴数据库对象");}
    }
}
