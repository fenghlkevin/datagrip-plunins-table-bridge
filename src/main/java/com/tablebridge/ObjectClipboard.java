package com.tablebridge;

import com.intellij.database.psi.DbElement;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import java.awt.datatransfer.*;
import java.util.*;

@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
public final class ObjectClipboard implements com.intellij.openapi.Disposable {
    private static final DataFlavor FLAVOR = new DataFlavor("application/x-tablebridge-token;class=java.lang.String", "TableBridge objects");
    private String token;
    private List<SmartPsiElementPointer<DbElement>> refs=List.of();
    @Override public void dispose(){refs=List.of();token=null;}
    static void copy(Project project,List<DbElement> objects) {
        var first=objects.getFirst();
        var ns=DbSelection.namespace(first);
        if(ns==null) throw new IllegalArgumentException("请选择模式中的对象");
        DbSelection.engine(first);
        for(var e:objects) if(!Objects.equals(ns,DbSelection.namespace(e))) throw new IllegalArgumentException("一次复制请选择同一连接、同一模式中的对象");
        ObjectClipboard clipboard=project.getService(ObjectClipboard.class);
        clipboard.refs=objects.stream().map(e->SmartPointerManager.getInstance(project).createSmartPsiElementPointer(e)).toList();
        clipboard.token=UUID.randomUUID().toString();final String id=clipboard.token;
        final String text=String.join("\n",objects.stream().map(DbElement::getName).toList());
        CopyPasteManager.getInstance().setContents(new Transferable(){
            public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{FLAVOR,DataFlavor.stringFlavor};}
            public boolean isDataFlavorSupported(DataFlavor f){return FLAVOR.equals(f)||DataFlavor.stringFlavor.equals(f);}
            public Object getTransferData(DataFlavor f)throws UnsupportedFlavorException{if(FLAVOR.equals(f))return id;if(DataFlavor.stringFlavor.equals(f))return text;throw new UnsupportedFlavorException(f);}
        });
    }
    static List<DbElement> read(Project project) {
        if(project==null || project.isDisposed()) return List.of();
        ObjectClipboard clipboard=project.getService(ObjectClipboard.class);
        if(clipboard.token==null)return List.of();
        if(!clipboard.token.equals(CopyPasteManager.getInstance().getContents(FLAVOR))) return List.of();
        var list=clipboard.refs.stream().map(SmartPsiElementPointer::getElement).toList();
        return list.stream().anyMatch(e->e==null||!e.isValid())?List.of():list;
    }
}
