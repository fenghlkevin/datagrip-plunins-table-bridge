package com.tablebridge;
import com.intellij.openapi.actionSystem.*;
import java.util.*;
public final class CopyPastePromoter implements ActionPromoter {
    @Override public List<AnAction> promote(List<? extends AnAction> actions,DataContext context){
        if(!DbSelection.inTree(context))return List.of();
        return actions.stream().filter(a->(a instanceof CopyObjectsAction && DbSelection.canCopy(context)) || (a instanceof PasteObjectsAction && DbSelection.pasteTarget(context)!=null && !ObjectClipboard.read(CommonDataKeys.PROJECT.getData(context)).isEmpty())).map(a->(AnAction)a).toList();
    }
}
