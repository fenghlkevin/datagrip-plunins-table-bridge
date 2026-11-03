package com.tablebridge;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.notification.*;
public final class CopyObjectsAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(AnActionEvent e){if(UiText.english()){e.getPresentation().setText("Copy database objects");e.getPresentation().setDescription("Copy object references, then paste onto a destination");}e.getPresentation().setEnabledAndVisible(e.getProject()!=null&&DbSelection.canCopy(e.getDataContext()));}
    @Override public void actionPerformed(AnActionEvent e){
        try{var es=DbSelection.elements(e.getDataContext());ObjectClipboard.copy(e.getProject(),es);
            NotificationGroupManager.getInstance().getNotificationGroup("TableBridge").createNotification(UiText.english()?"Copied "+es.size()+" database objects":"已复制 "+es.size()+" 个数据库对象", UiText.english()?"Select the destination database or schema and paste to open Data Transfer.":"选中目标数据库或模式，按粘贴打开数据传输。",NotificationType.INFORMATION).notify(e.getProject());
        }catch(IllegalArgumentException ex){Messages.showErrorDialog(e.getProject(),ex.getMessage(),"复制数据库对象");}
    }
}
