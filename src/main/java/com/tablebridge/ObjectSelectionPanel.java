package com.tablebridge;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/** A real, independently testable Swing panel shared by the wizard and UI smoke test. */
final class ObjectSelectionPanel extends JPanel {
    static final List<String> GROUPS=List.of("表","视图","函数","存储过程","序列","类型");
    static class Item {
        final String originalName,group;
        String name,objectSql="",signature="";
        String displayName(){return originalName+(signature.isBlank()?"":" · "+signature);}
        Set<String> dependencies=new LinkedHashSet<>();
        boolean checked,custom,data=true;
        Map<String,FieldMapping> mappings=new LinkedHashMap<>();
        Item(String name,String group,boolean checked){this.originalName=name;this.name=name;this.group=group;this.checked=checked;}
        boolean table(){return group.equals("表");}
        String target(){return custom?name:originalName;}
        boolean copyData(boolean global){return table()&&(custom?data:global);}
    }
    final JTree tree=new JTree();
    final JBTextField search=new JBTextField();
    final JTextField targetName=new JTextField();
    final JRadioButton auto=new JRadioButton(UiText.text("自动"),true),advanced=new JRadioButton(UiText.text("高级"));
    final JComboBox<String> content=new JComboBox<>(new String[]{UiText.text("结构和数据"),UiText.text("仅结构")});
    final JLabel title=new JLabel(),subtitle=new JLabel(),automatic=new JLabel();
    final JPanel config=new JPanel();
    final JCheckBox records,foreignKeys;
    private final List<? extends Item> items;
    private final Runnable changed;
    private final String namespace;
    private final Map<String,Boolean> expanded=new HashMap<>();
    private Item current;
    java.util.function.Consumer<Item> mappingEditor,objectEditor;
    private final JButton objectButton=new JButton(UiText.text("转换定义…"));
    private final JButton mappingButton=new JButton(UiText.text("字段映射…"));
    private boolean updating,rebuilding,filtering;
    private final JPanel dataField;

    ObjectSelectionPanel(List<? extends Item> items,String namespace,JCheckBox records,JCheckBox foreignKeys,Runnable changed){
        super(new BorderLayout(0,JBUI.scale(14)));this.items=items;this.namespace=namespace;this.records=records;this.foreignKeys=foreignKeys;this.changed=changed;
        GROUPS.forEach(g->expanded.put(g,g.equals("表")||g.equals("视图")));
        JPanel left=new JPanel(new BorderLayout(0,JBUI.scale(8)));left.setBorder(JBUI.Borders.empty(10,0,8,0));
        JPanel searchWrap=new JPanel(new BorderLayout());searchWrap.setBorder(JBUI.Borders.empty(0,10));
        search.getEmptyText().setText(UiText.text("搜索对象…"));search.getAccessibleContext().setAccessibleName(UiText.text("搜索对象"));searchWrap.add(search);left.add(searchWrap,BorderLayout.NORTH);
        tree.setRootVisible(false);tree.setShowsRootHandles(true);tree.setRowHeight(JBUI.scale(29));tree.setCellRenderer(new Renderer());tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(e->{if(tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode n&&n.getUserObject() instanceof Item i)select(i);});
        tree.addTreeExpansionListener(new TreeExpansionListener(){public void treeExpanded(TreeExpansionEvent e){remember(e,true);}public void treeCollapsed(TreeExpansionEvent e){remember(e,false);}private void remember(TreeExpansionEvent e,boolean value){if(!rebuilding&&!filtering)expanded.put(e.getPath().getLastPathComponent().toString(),value);}});
        tree.addMouseListener(new MouseAdapter(){public void mousePressed(MouseEvent e){TreePath path=tree.getPathForLocation(e.getX(),e.getY());if(path==null)return;Rectangle b=tree.getPathBounds(path);if(b!=null&&e.getX()>=b.x&&e.getX()<b.x+JBUI.scale(23)&&((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject() instanceof Item i){i.checked=!i.checked;selectionChanged();}}});
        tree.getInputMap().put(KeyStroke.getKeyStroke("SPACE"),"toggle-copy");tree.getActionMap().put("toggle-copy",new AbstractAction(){public void actionPerformed(ActionEvent e){if(tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode n){Object o=n.getUserObject();if(o instanceof Item i)i.checked=!i.checked;else{List<? extends Item> group=items.stream().filter(i->i.group.equals(o)&&matches(i)).toList();boolean check=group.stream().anyMatch(i->!i.checked);group.forEach(i->i.checked=check);}selectionChanged();}}});
        JBScrollPane scroll=new JBScrollPane(tree);scroll.setBorder(JBUI.Borders.empty());left.add(scroll);
        JPanel right=new JPanel(new BorderLayout());right.setBackground(tree.getBackground());right.setBorder(JBUI.Borders.empty(20));right.setMinimumSize(JBUI.size(280,200));left.setMinimumSize(JBUI.size(220,200));
        title.setFont(title.getFont().deriveFont(Font.BOLD,title.getFont().getSize2D()+2));subtitle.setForeground(JBColor.GRAY);automatic.setForeground(JBColor.GRAY);
        JPanel detail=new JPanel();detail.setLayout(new BoxLayout(detail,BoxLayout.Y_AXIS));
        addLine(detail,title,0);addLine(detail,subtitle,4);
        JPanel modes=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));ButtonGroup buttons=new ButtonGroup();buttons.add(auto);buttons.add(advanced);modes.add(auto);modes.add(Box.createHorizontalStrut(JBUI.scale(20)));modes.add(advanced);addLine(detail,modes,22);
        addLine(detail,automatic,14);config.setLayout(new BoxLayout(config,BoxLayout.Y_AXIS));addLine(config,field(UiText.text("目标名称"),targetName),0);dataField=field(UiText.text("复制内容"),content);addLine(config,dataField,12);addLine(config,mappingButton,12);addLine(config,objectButton,12);objectButton.addActionListener(e->{if(current!=null&&objectEditor!=null)objectEditor.accept(current);});mappingButton.addActionListener(e->{if(current!=null&&mappingEditor!=null)mappingEditor.accept(current);});addLine(detail,config,14);
        JSeparator line=new JSeparator();line.setMaximumSize(new Dimension(Integer.MAX_VALUE,JBUI.scale(1)));addLine(detail,line,20);
        JLabel help=new JLabel(UiText.text("<html>勾选决定复制范围。<br>点击对象名称可单独配置。</html>"));help.setForeground(JBColor.GRAY);addLine(detail,help,14);transparentPanels(detail);JBScrollPane detailScroll=new JBScrollPane(detail);detailScroll.setBorder(JBUI.Borders.empty());detailScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);detailScroll.getViewport().setBackground(tree.getBackground());right.add(detailScroll,BorderLayout.CENTER);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,left,right);split.setBorder(JBUI.Borders.customLine(JBColor.border()));split.setDividerSize(JBUI.scale(1));split.setResizeWeight(.46);split.setDividerLocation(JBUI.scale(350));add(split);
        JPanel options=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));options.add(records);options.add(Box.createHorizontalStrut(JBUI.scale(22)));options.add(foreignKeys);foreignKeys.setToolTipText(UiText.text("依赖表需同时复制，或已存在于目标位置"));add(options,BorderLayout.SOUTH);
        auto.addActionListener(e->mode(false));advanced.addActionListener(e->mode(true));records.addActionListener(e->{refreshDetail();changed.run();});foreignKeys.addActionListener(e->changed.run());
        targetName.getDocument().addDocumentListener(listener(()->{if(!updating&&current!=null){current.name=targetName.getText();changed.run();}}));content.addActionListener(e->{if(!updating&&current!=null){current.data=content.getSelectedIndex()==0;changed.run();}});
        search.getDocument().addDocumentListener(listener(this::rebuild));current=items.stream().filter(i->i.checked).findFirst().orElse(null);if(current==null&&!items.isEmpty())current=items.getFirst();rebuild();refreshDetail();
    }
    private static void transparentPanels(Container c){for(Component child:c.getComponents())if(child instanceof JPanel p){p.setOpaque(false);transparentPanels(p);}if(c instanceof JPanel p)p.setOpaque(false);}
    private static void addLine(JPanel p,JComponent c,int gap){if(gap>0)p.add(Box.createVerticalStrut(JBUI.scale(gap)));c.setAlignmentX(Component.LEFT_ALIGNMENT);Dimension d=c.getPreferredSize();int height=c instanceof JLabel?Math.max(d.height,c.getFontMetrics(c.getFont()).getHeight()):d.height;c.setMaximumSize(new Dimension(Integer.MAX_VALUE,height));p.add(c);}
    static JPanel field(String name,JComponent control){JPanel p=new JPanel(new BorderLayout(0,JBUI.scale(5)));JLabel label=new JLabel(UiText.text(name));label.setForeground(JBColor.GRAY);label.setLabelFor(control);p.add(label,BorderLayout.NORTH);p.add(control);return p;}
    void selectNames(Set<String> names){items.stream().filter(i->names.contains(i.originalName)).forEach(i->i.checked=true);rebuild();changed.run();}
    void refreshChoices(){rebuild();refreshDetail();changed.run();}
    long selectedCount(){return items.stream().filter(i->i.checked).count();}
    String validation(){if(selectedCount()==0)return UiText.text("请至少选择一个对象");Set<String> names=new HashSet<>();for(Item i:items)if(i.checked){if(i.target().isBlank())return UiText.text("请填写目标对象名称");if(!names.add(i.target().toLowerCase(Locale.ROOT)))return UiText.text("目标对象名称重复：")+i.target();}return null;}
    private void mode(boolean custom){if(current!=null){if(custom&&!current.custom)current.data=records.isSelected();current.custom=custom;refreshDetail();changed.run();}}
    private void select(Item i){current=i;refreshDetail();}
    private void refreshDetail(){updating=true;boolean present=current!=null;auto.setEnabled(present);advanced.setEnabled(present);title.setText(present?current.displayName():UiText.text("没有可选对象"));subtitle.setText(present?UiText.text(current.group)+" · "+namespace:"");config.setVisible(present&&current.custom);automatic.setVisible(present&&!current.custom);if(present){auto.setSelected(!current.custom);advanced.setSelected(current.custom);targetName.setText(current.name);content.setSelectedIndex(current.data?0:1);dataField.setVisible(current.table());mappingButton.setVisible(current.table()&&mappingEditor!=null);objectButton.setVisible(objectEditor!=null);objectButton.setText(current.table()?UiText.text("完整结构…"):UiText.text("转换定义…"));automatic.setText(current.table()?(records.isSelected()?UiText.text("保留原名称，复制结构和数据。"):UiText.text("保留原名称，仅复制结构。")) : UiText.text("保留原名称，复制对象定义。"));}updating=false;revalidate();repaint();}
    private void selectionChanged(){tree.repaint();changed.run();}
    private boolean matches(Item i){return i.originalName.toLowerCase(Locale.ROOT).contains(search.getText().trim().toLowerCase(Locale.ROOT));}
    private void rebuild(){rebuilding=true;filtering=!search.getText().isBlank();DefaultMutableTreeNode root=new DefaultMutableTreeNode("对象");TreePath selected=null;for(String g:GROUPS){DefaultMutableTreeNode group=new DefaultMutableTreeNode(g);root.add(group);for(Item i:items)if(i.group.equals(g)&&matches(i)){DefaultMutableTreeNode n=new DefaultMutableTreeNode(i);group.add(n);if(i==current)selected=new TreePath(n.getPath());}}tree.setModel(new DefaultTreeModel(root));for(int i=0;i<root.getChildCount();i++){DefaultMutableTreeNode n=(DefaultMutableTreeNode)root.getChildAt(i);if(filtering||expanded.get(n.toString()))tree.expandPath(new TreePath(n.getPath()));}if(selected!=null)tree.setSelectionPath(selected);rebuilding=false;}
    private final class Renderer implements TreeCellRenderer{
        public Component getTreeCellRendererComponent(JTree t,Object value,boolean selected,boolean open,boolean leaf,int row,boolean focus){Object o=((DefaultMutableTreeNode)value).getUserObject();JPanel panel=new JPanel(new BorderLayout(JBUI.scale(6),0));panel.setOpaque(true);panel.setBackground(selected?new JBColor(0xEDF3FF,0x303E58):t.getBackground());JLabel label=new JLabel();label.setFont(t.getFont().deriveFont(Font.PLAIN));label.setForeground(t.getForeground());if(o instanceof Item i){JCheckBox check=new JCheckBox();check.setSelected(i.checked);check.setOpaque(false);check.setPreferredSize(JBUI.size(21,25));panel.add(check,BorderLayout.WEST);label.setText(i.displayName());label.setIcon(switch(i.group){case "表","视图"->AllIcons.Nodes.DataTables;case "函数"->AllIcons.Nodes.Function;case "存储过程"->AllIcons.Nodes.Method;default->AllIcons.Nodes.Class;});label.setIconTextGap(JBUI.scale(7));}else{long count=items.stream().filter(i->i.group.equals(o)).count(),checked=items.stream().filter(i->i.group.equals(o)&&i.checked).count();label.setText(UiText.text(String.valueOf(o))+" ("+checked+"/"+count+")");label.setFont(label.getFont().deriveFont(Font.BOLD));}panel.add(label);panel.setBorder(JBUI.Borders.emptyRight(8));panel.setPreferredSize(new Dimension(panel.getPreferredSize().width,JBUI.scale(29)));return panel;}
    }
    private static DocumentListener listener(Runnable f){return new DocumentListener(){public void insertUpdate(DocumentEvent e){f.run();}public void removeUpdate(DocumentEvent e){f.run();}public void changedUpdate(DocumentEvent e){f.run();}};}
}
