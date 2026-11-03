package com.tablebridge;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Renders and exercises the production panel using the running IDE's actual Swing theme. */
final class UiSmoke {
    static void run(Path output)throws Exception{
        List<ObjectSelectionPanel.Item> items=new ArrayList<>();
        items.add(new ObjectSelectionPanel.Item("sys_user","表",true));items.add(new ObjectSelectionPanel.Item("sys_role","表",true));items.add(new ObjectSelectionPanel.Item("sys_menu","表",false));items.add(new ObjectSelectionPanel.Item("v_user_role","视图",true));items.add(new ObjectSelectionPanel.Item("fn_user_name","函数",false));items.add(new ObjectSelectionPanel.Item("proc_sync_user","存储过程",false));
        JCheckBox data=new JCheckBox("复制表数据",true),fk=new JCheckBox("复制外键");int[] updates={0};
        ObjectSelectionPanel panel=new ObjectSelectionPanel(items,"moi",data,fk,()->updates[0]++);
        panel.mappingEditor=item->item.mappings.put("labels",new FieldMapping(Engine.MYSQL,"longtext",true,false));
        check(panel.selectedCount()==3,"initial selection count");
        check(((DefaultMutableTreeNode)panel.tree.getModel().getRoot()).getChildCount()==6,"all six groups including empty groups");
        check(panel.automatic.getText().equals(UiText.text("保留原名称，复制结构和数据。")),"automatic explanation");
        data.doClick();check(!items.getFirst().copyData(data.isSelected()),"global data off");
        panel.advanced.doClick();check(items.getFirst().custom&&!items.getFirst().data,"advanced inherits global data");
        panel.targetName.setText("sys_user_copy");panel.content.setSelectedIndex(0);
        check(items.getFirst().target().equals("sys_user_copy")&&items.getFirst().copyData(false),"advanced name and data override feed plan");
        panel.targetName.setText("");check(panel.validation()!=null,"empty target disables advance");panel.targetName.setText("sys_role");check(panel.validation()!=null,"duplicate target disables advance");
        panel.targetName.setText("sys_user_copy");panel.auto.doClick();check(items.getFirst().target().equals("sys_user"),"automatic restores original target");
        panel.search.setText("v_user");check(panel.selectedCount()==3,"search preserves selected hidden objects");
        panel.search.setText("");data.doClick();
        DefaultMutableTreeNode root=(DefaultMutableTreeNode)panel.tree.getModel().getRoot();DefaultMutableTreeNode tables=(DefaultMutableTreeNode)root.getChildAt(0);
        panel.tree.setSelectionPath(new TreePath(((DefaultMutableTreeNode)tables.getChildAt(0)).getPath()));
        panel.tree.getActionMap().get("toggle-copy").actionPerformed(null);check(panel.selectedCount()==2,"keyboard toggles object");panel.tree.getActionMap().get("toggle-copy").actionPerformed(null);
        panel.tree.setSelectionPath(new TreePath(tables.getPath()));panel.tree.getActionMap().get("toggle-copy").actionPerformed(null);check(panel.selectedCount()==4,"group keyboard select all");panel.tree.getActionMap().get("toggle-copy").actionPerformed(null);check(panel.selectedCount()==1,"group clear");items.get(0).checked=true;items.get(1).checked=true;
        panel.tree.setSelectionPath(new TreePath(((DefaultMutableTreeNode)tables.getChildAt(0)).getPath()));
        check(updates[0]>6,"changes notify wizard");
        capture(panel,output.resolveSibling("ui-objects.png"),780,430);
        panel.advanced.doClick();capture(panel,output.resolveSibling("ui-advanced.png"),780,430);
        check(panel.targetName.getHeight()<45,"normal input height");
        check(panel.targetName.getWidth()>180,"usable target name field");
        Files.writeString(output.resolveSibling("ui-result.txt"),"PASS production Swing panel: six groups, selected count, global data, per-object overrides, blank/duplicate validation, search persistence, keyboard item/group selection, change notifications, field dimensions.\n");
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void capture(JComponent panel,Path path,int width,int height)throws Exception{JFrame frame=new JFrame("TableBridge UI test");frame.setContentPane(panel);frame.setSize(width+20,height+40);frame.setVisible(true);panel.setSize(width,height);layout(panel);BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();panel.printAll(g);g.dispose();ImageIO.write(image,"png",path.toFile());frame.dispose();}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
}
