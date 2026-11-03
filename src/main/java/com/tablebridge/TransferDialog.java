package com.tablebridge;

import com.intellij.database.psi.*;
import com.intellij.database.util.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.*;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.ui.table.JBTable;
import com.intellij.openapi.ui.Messages;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

final class TransferDialog extends DialogWrapper {
    private final Project project;
    private final DbElement sourceNs;
    private final List<Row> rows=new ArrayList<>();
    private final JComboBox<Destination> destinations=new JComboBox<>();
    private final JPanel pages=new JPanel(new CardLayout());
    private final JLabel direction=new JLabel("",SwingConstants.CENTER),status=new JLabel(UiText.text("同类型数据库对象复制 · 不覆盖已有对象"));
    private final JButton previous=new JButton(UiText.text("上一步")),next=new JButton(UiText.text("下一步"));
    private final JCheckBox records=new JCheckBox(UiText.text("复制表数据"),true),foreignKeys=new JCheckBox(UiText.text("复制外键"),false),keepGoing=new JCheckBox(UiText.text("遇到错误时继续"),false);
    private ObjectSelectionPanel objects;
    private final JComboBox<DbDataSource> connections=new JComboBox<>();
    private final java.util.List<Destination> allDestinations=new ArrayList<>();
    private final JLabel[] steps={new JLabel(UiText.text("①  源与目标")),new JLabel(UiText.text("②  选择对象")),new JLabel(UiText.text("③  确认传输"))};
    private final JLabel selectionCount=new JLabel();
    private final DefaultTableModel summaryModel=new DefaultTableModel(new Object[]{UiText.text("类型"),UiText.text("源对象"),UiText.text("目标对象"),UiText.text("复制内容"),UiText.text("目标操作")},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTextArea sql=new JTextArea(),logs=new JTextArea(),diff=new JTextArea();
    private final JButton previewDiff=new JButton(UiText.text("刷新差异预览"));
    private int step=0;
    
    private boolean running=false,building=false;
    private TransferPlan plan;
    private TransferControl transferControl;
    private TransferReport.Snapshot latestReport;
    private final JComboBox<ExistingPolicy> existingPolicy=new JComboBox<>(ExistingPolicy.values());
    private final JCheckBox recoverable=new JCheckBox(UiText.text("启用断点与恢复（需要完整主键）"));
    private final JCheckBox verifyContent=new JCheckBox(UiText.text("全量内容校验（额外读取数据）"));
    private final JCheckBox acknowledge=new JCheckBox(UiText.text("我已检查转换说明和目标操作，确认执行"));
    private final JTextArea warnings=new JTextArea(3,30);
    private final JButton cancelTransfer=new JButton(UiText.text("取消传输")),exportReport=new JButton(UiText.text("导出结果…"));
    private final DefaultTableModel resultModel=new DefaultTableModel(new Object[]{UiText.text("目标对象"),UiText.text("状态"),UiText.text("已处理行数"),UiText.text("耗时"),UiText.text("提交"),UiText.text("说明")},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JLabel resultSummary=new JLabel(UiText.text("准备传输…"));
    private static final class Row extends ObjectSelectionPanel.Item {
        final DbElement source;
        Row(DbElement e,boolean checked){super(e.getName(),DbSelection.group(e),checked);source=e;signature=ObjectIdentity.signature(e);}
    }
    private record Destination(DbElement element){@Override public String toString(){return DbSelection.path(element);}}
    TransferDialog(Project project,List<DbElement> copied,DbElement target){
        super(project,false);this.project=project;sourceNs=DbSelection.namespace(copied.getFirst());
        for(var basic:DbSelection.basic(sourceNs).getChildren())if(DbSelection.KINDS.contains(basic.getKind())){
            DbElement e=sourceNs.getDataSource().findElement(basic);if(e!=null)rows.add(new Row(e,copied.contains(e)));
        }
        for(var e:copied)if(rows.stream().noneMatch(r->r.source.equals(e)))rows.add(new Row(e,true));
        rows.sort(Comparator.comparing((Row r)->DbSelection.group(r.source)).thenComparing(r->r.source.getName()));
        allDestinations.add(new Destination(target));
        for(var ds:DbUtil.getDataSources(project)){
            try{if(!CrossEngineDdl.supports(DbSelection.engine(sourceNs),DbSelection.engine(ds)))continue;}catch(IllegalArgumentException unsupported){continue;}
            for(var ns:DasUtil.getSchemas(ds)){var e=ds.findElement(ns);if(e!=null&&!e.equals(target))allDestinations.add(new Destination(e));}
        }
        for(Destination d:allDestinations)if(java.util.stream.IntStream.range(0,connections.getItemCount()).noneMatch(i->connections.getItemAt(i).equals(d.element.getDataSource())))connections.addItem(d.element.getDataSource());
        connections.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean selected,boolean focus){return super.getListCellRendererComponent(l,v instanceof DbDataSource ds?ds.getName():v,i,selected,focus);}});
        destinations.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean selected,boolean focus){String name=v instanceof Destination d?namespaceLabel(d.element):"";return super.getListCellRendererComponent(l,name,i,selected,focus);}});
        filterDestinations();connections.addActionListener(e->filterDestinations());
        setTitle(UiText.text("数据传输 — TableBridge"));setResizable(true);init();getRootPane().setDefaultButton(next);showStep(0);setSize(JBUI.scale(840),JBUI.scale(640));getWindow().setMinimumSize(JBUI.size(720,580));
    }
    private static String namespaceLabel(DbElement e){String catalog=DasUtil.getCatalog(e);return catalog==null||catalog.isBlank()||catalog.equals(e.getName())?e.getName():catalog+" / "+e.getName();}
    private void filterDestinations(){Object ds=connections.getSelectedItem();destinations.removeAllItems();for(Destination d:allDestinations)if(d.element.getDataSource().equals(ds))destinations.addItem(d);updateDirection();}
    @Override protected JComponent createCenterPanel(){
        JPanel root=new JPanel(new BorderLayout());root.setPreferredSize(JBUI.size(792,470));
        JPanel header=new JPanel(new BorderLayout());JPanel progress=new JPanel(new GridLayout(1,3,JBUI.scale(20),0));progress.setBorder(JBUI.Borders.empty(10,16,16,16));for(JLabel label:steps)progress.add(label);header.add(progress,BorderLayout.NORTH);
        direction.setHorizontalAlignment(SwingConstants.LEFT);direction.setForeground(JBColor.GRAY);direction.setBorder(JBUI.Borders.empty(8,16));header.add(direction,BorderLayout.SOUTH);root.add(header,BorderLayout.NORTH);
        pages.setBorder(JBUI.Borders.empty(16));pages.add(connectionPage(),"0");pages.add(objectPage(),"1");pages.add(summaryPage(),"2");logs.setEditable(false);pages.add(resultPage(),"3");root.add(pages);
        status.setForeground(JBColor.GRAY);status.setBorder(JBUI.Borders.empty(0,16,8,16));root.add(status,BorderLayout.SOUTH);
        destinations.addActionListener(e->updateDirection());return root;
    }
    private JPanel connectionPage(){
        JPanel page=new JPanel(new BorderLayout(0,JBUI.scale(16)));
        JLabel heading=new JLabel(UiText.text("确认复制位置"));heading.setFont(heading.getFont().deriveFont(Font.BOLD));page.add(heading,BorderLayout.NORTH);
        JPanel columns=new JPanel(new GridLayout(1,2,JBUI.scale(24),0));
        JTextField sourceConnection=new JTextField(sourceNs.getDataSource().getName()),sourceName=new JTextField(namespaceLabel(sourceNs));sourceConnection.setEditable(false);sourceName.setEditable(false);
        columns.add(connectionForm(UiText.text("源"),sourceConnection,sourceName,sourceNs.getDataSource().getDbms().getDisplayName()+UiText.text(" · 已复制 ")+rows.stream().filter(r->r.checked).count()+UiText.text(" 个对象")));
        columns.add(connectionForm(UiText.text("目标"),connections,destinations,UiText.text("已带入粘贴位置")));
        JPanel top=new JPanel(new BorderLayout(0,JBUI.scale(14)));top.add(columns,BorderLayout.NORTH);JLabel hint=new JLabel(UiText.text("下一步可调整对象范围、目标名称和复制内容。"));hint.setForeground(JBColor.GRAY);top.add(hint,BorderLayout.SOUTH);JPanel wrap=new JPanel(new BorderLayout());wrap.add(top,BorderLayout.NORTH);page.add(wrap,BorderLayout.CENTER);return page;
    }
    private static JPanel connectionForm(String title,JComponent connection,JComponent namespace,String hint){
        JPanel panel=new JPanel(new GridBagLayout());panel.setBorder(BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border()),JBUI.Borders.empty(16)));
        GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;c.anchor=GridBagConstraints.NORTHWEST;
        JLabel heading=new JLabel(UiText.text(title));heading.setFont(heading.getFont().deriveFont(Font.BOLD));JLabel foot=new JLabel(UiText.text(hint));foot.setForeground(JBColor.GRAY);
        JComponent[] components={heading,ObjectSelectionPanel.field(UiText.text("连接"),connection),ObjectSelectionPanel.field(UiText.text("数据库 / 模式"),namespace),foot};
        for(int i=0;i<components.length;i++){c.gridy=i;c.insets=JBUI.insets(i==0?0:14,0,0,0);panel.add(components[i],c);}return panel;
    }
    private JPanel objectPage(){
        objects=new ObjectSelectionPanel(rows,sourceNs.getName(),records,foreignKeys,this::selectionChanged);
        objects.mappingEditor=this::editMappings;objects.objectEditor=this::editObjectSql;
        JPanel wrapper=new JPanel(new BorderLayout(0,JBUI.scale(10)));JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT,JBUI.scale(8),0));controls.add(new JLabel(UiText.text("目标表已存在时")));controls.add(existingPolicy);JButton dependencies=new JButton(UiText.text("补选依赖"));dependencies.addActionListener(e->selectDependencies());controls.add(dependencies);
        JPanel toolbar=new JPanel(new BorderLayout(0,JBUI.scale(8)));toolbar.add(controls,BorderLayout.NORTH);
        JPanel files=new JPanel(new FlowLayout(FlowLayout.LEFT,JBUI.scale(8),0));JButton save=new JButton(UiText.text("保存配置…")),load=new JButton(UiText.text("加载配置…"));save.addActionListener(e->configurationFile(true));load.addActionListener(e->configurationFile(false));files.add(save);files.add(load);JPanel options=new JPanel(new FlowLayout(FlowLayout.LEFT,JBUI.scale(8),0));options.add(verifyContent);options.add(recoverable);toolbar.add(options,BorderLayout.CENTER);JButton recovery=new JButton(UiText.text("恢复任务…"));recovery.addActionListener(e->restoreJob());files.add(recovery);JButton cleanup=new JButton(UiText.text("清理当前任务暂存"));cleanup.addActionListener(e->{if(plan==null||!plan.recoverable||running||building)return;DurableRunner.cleanup(project,plan,message->status.setText(message));});files.add(cleanup);toolbar.add(files,BorderLayout.SOUTH);wrapper.add(toolbar,BorderLayout.NORTH);wrapper.add(objects);return wrapper;
    }
    private int confirmEditor(Object body,String title,int optionType,int messageType){
        return JOptionPane.showOptionDialog(getContentPane(),body,title,optionType,messageType,null,
            new Object[]{UiText.english()?"Save":"保存",UiText.english()?"Cancel":"取消"},null);
    }
    private void editObjectSql(ObjectSelectionPanel.Item item){
        Row row=(Row)item;Destination destination=(Destination)destinations.getSelectedItem();Engine from=DbSelection.engine(sourceNs),to=DbSelection.engine(destination.element);
        String original;
        try{original=PlanBuilder.sourceDefinition(project,row.source);}catch(Exception error){original=UiText.text("来源 DDL 尚未加载：")+error.getMessage();}
        JTextArea source=new JTextArea(original,18,40);source.setEditable(false);JTextArea target=new JTextArea(row.objectSql,18,40);source.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));target.setFont(source.getFont());
        java.util.List<String> dependencyNames=rows.stream().filter(r->r!=row).map(r->r.originalName).toList();
        JList<String> dependencies=new JList<>(dependencyNames.toArray(String[]::new));dependencies.setVisibleRowCount(4);dependencies.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        dependencies.setSelectedIndices(java.util.stream.IntStream.range(0,dependencyNames.size()).filter(i->row.dependencies.contains(dependencyNames.get(i))).toArray());
        JLabel feedback=new JLabel((row.table()?UiText.text("每条结构用 -- @tablebridge-next 分隔；首条 CREATE TABLE，触发器最后创建。目标："):UiText.text("目标定义必须创建 "))+to.qualified(destination.element.getName(),row.target()));
        JButton generate=new JButton(UiText.text("尝试自动转换"));generate.addActionListener(e->{try{
            Map<String,String> names=new LinkedHashMap<>();rows.stream().filter(r->r.checked||r==row).forEach(r->names.put(r.originalName,r.target()));
            target.setText(row.table()||from==to?PlanBuilder.structurePreview(project,new PlanBuilder.Choice(row.source,row.target(),false,row.mappings),destination.element,foreignKeys.isSelected(),names):PlanBuilder.automaticObjectDefinition(row.source,row.target(),destination.element,names));target.setCaretPosition(0);feedback.setText(UiText.text("已生成；请审核后保存"));
        }catch(Exception error){feedback.setText(error.getMessage());}});
        if(row.objectSql.isBlank())generate.doClick();
        JPanel body=new JPanel(new BorderLayout(8,8));body.setPreferredSize(JBUI.size(820,500));JPanel editors=new JPanel(new GridLayout(1,2,8,0));editors.add(ObjectSelectionPanel.field(UiText.text("来源定义（只读）"),new JBScrollPane(source)));editors.add(ObjectSelectionPanel.field(UiText.text("目标定义（可修改）"),new JBScrollPane(target)));body.add(editors);JPanel foot=new JPanel(new BorderLayout(0,6));foot.add(generate,BorderLayout.WEST);foot.add(feedback,BorderLayout.SOUTH);if(row.table())foot.add(new JLabel(UiText.text("<html>排序规则放在 CREATE TABLE 中；附属语句用 -- @tablebridge-next 单独一行分隔。<br>触发器最后创建；字段类型有修改时，请同步填写字段映射。留空恢复自动。</html>")),BorderLayout.NORTH);JPanel bottom=new JPanel(new BorderLayout(0,6));bottom.add(ObjectSelectionPanel.field(UiText.text("先创建的依赖（Ctrl/⌘ 多选；保存时补选）"),new JBScrollPane(dependencies)),BorderLayout.NORTH);bottom.add(foot,BorderLayout.SOUTH);body.add(bottom,BorderLayout.SOUTH);
        while(confirmEditor(body,(row.table()?UiText.text("完整结构 · "):UiText.text("转换定义 · "))+row.originalName,JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION){try{
            row.objectSql=target.getText().isBlank()?"":row.table()?TableStructureSql.validate(to,destination.element.getName(),row.target(),target.getText()).text():CustomObjectSql.validate(to,destination.element.getName(),row.target(),PlanBuilder.objectSqlKind(row.source),target.getText());
            row.dependencies=new LinkedHashSet<>(dependencies.getSelectedValuesList());rows.stream().filter(r->row.dependencies.contains(r.originalName)).forEach(r->r.checked=true);objects.refreshChoices();
            plan=null;acknowledge.setSelected(false);selectionChanged();status.setText(row.objectSql.isBlank()?UiText.text("已恢复自动对象转换"):UiText.text("手工目标定义已保存；下一步重新审核计划"));break;
        }catch(IllegalArgumentException error){Messages.showErrorDialog(project,error.getMessage(),UiText.text("目标定义无效"));}}
    }
    private void editMappings(ObjectSelectionPanel.Item item){
        Row row=(Row)item;Destination destination=(Destination)destinations.getSelectedItem();Engine from=DbSelection.engine(sourceNs),to=DbSelection.engine(destination.element);
        if(from==to){Messages.showInfoMessage(project,"同类型复制使用原生对象定义；字段映射用于跨类型复制。",UiText.text("字段映射"));return;}
        var columns=DasUtil.getColumns(row.source).toList();
        DefaultTableModel model=new DefaultTableModel(new Object[]{UiText.text("字段"),UiText.text("来源类型"),UiText.text("目标类型（留空自动）"),UiText.text("不复制默认值"),UiText.text("计算值物化")},0){
            public boolean isCellEditable(int r,int c){return c>=2;}
            public Class<?> getColumnClass(int c){return c>=3?Boolean.class:String.class;}
        };
        for(var column:columns){FieldMapping mapping=row.mappings.get(column.getName());model.addRow(new Object[]{column.getName(),column.getDataType().getSpecification(),mapping==null?"":mapping.type(),mapping!=null&&mapping.omitDefault(),mapping!=null&&mapping.materialize()});}
        JBTable table=new JBTable(model);table.setRowHeight(JBUI.scale(28));JBScrollPane scroll=new JBScrollPane(table);scroll.setPreferredSize(JBUI.size(780,320));
        JPanel panel=new JPanel(new BorderLayout(0,JBUI.scale(10)));panel.add(new JLabel("<html>目标："+to+"。填写目标数据库类型，例如 varchar(200)、text、number(20,4)。<br>物化计算值后不再自动计算；复杂类型转为文本会改变字段语义。所有选择将在计划中列出。</html>"),BorderLayout.NORTH);panel.add(scroll);
        while(confirmEditor(panel,UiText.text("字段映射 · ")+row.originalName,JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION){
            try{
                if(table.isEditing()&&!table.getCellEditor().stopCellEditing())continue;
                Map<String,FieldMapping> mappings=new LinkedHashMap<>();
                for(int i=0;i<model.getRowCount();i++){String name=(String)model.getValueAt(i,0),type=String.valueOf(model.getValueAt(i,2)).trim();boolean omit=Boolean.TRUE.equals(model.getValueAt(i,3)),materialize=Boolean.TRUE.equals(model.getValueAt(i,4));
                    if(type.isEmpty()){if(omit||materialize)throw new IllegalArgumentException("请为手工选项填写目标类型："+name);continue;}
                    if(materialize&&!DasUtil.isComputed(columns.get(i)))throw new IllegalArgumentException("物化仅适用于计算列："+name);
                    mappings.put(name,new FieldMapping(to,type,omit,materialize));
                }
                row.mappings=mappings;plan=null;acknowledge.setSelected(false);selectionChanged();status.setText("已保存 "+mappings.size()+" 个字段映射；下一步重新检查计划");break;
            }catch(IllegalArgumentException ex){Messages.showErrorDialog(project,ex.getMessage(),UiText.text("字段映射无效"));}
        }
    }
    private void configurationFile(boolean save){
        if(running||building)return;
        JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("tablebridge-config.xml"));
        if((save?chooser.showSaveDialog(getContentPane()):chooser.showOpenDialog(objects))!=JFileChooser.APPROVE_OPTION)return;
        var file=chooser.getSelectedFile().toPath();
        try{
            if(save){
                if(objects.validation()!=null)throw new IllegalArgumentException(objects.validation());
                if(Files.exists(file)&&JOptionPane.showConfirmDialog(getContentPane(),"覆盖已有配置？","保存配置",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
                var destination=(Destination)destinations.getSelectedItem();
                var settings=new TransferConfiguration.Settings(DbSelection.path(sourceNs),DbSelection.path(destination.element),records.isSelected(),foreignKeys.isSelected(),keepGoing.isSelected(),(ExistingPolicy)existingPolicy.getSelectedItem(),rows.stream().filter(r->r.checked).map(r->new TransferConfiguration.ObjectChoice(r.originalName,r.group,r.custom?r.name:r.originalName,r.custom,r.data,r.mappings,r.objectSql,r.dependencies,r.signature)).toList(),verifyContent.isSelected(),recoverable.isSelected());
                TransferConfiguration.save(file,settings);status.setText("配置已保存");
            }else{
                var settings=TransferConfiguration.load(file);
                if(!settings.source().equals(DbSelection.path(sourceNs)))throw new IllegalArgumentException("配置来源与当前来源不匹配，请先复制配置中的来源对象");
                Destination destination=allDestinations.stream().filter(d->DbSelection.path(d.element).equals(settings.target())).findFirst().orElseThrow(()->new IllegalArgumentException("目标位置未加载，请先连接并内省配置中的目标"));
                Map<TransferConfiguration.ObjectChoice,Row> matched=new LinkedHashMap<>();
                for(var choice:settings.objects()){
                    var matches=rows.stream().filter(r->r.originalName.equals(choice.source())&&r.group.equals(choice.group())&&(choice.signature().isBlank()||r.signature.equals(choice.signature()))).toList();
                    if(matches.size()!=1)throw new IllegalArgumentException("来源未加载或存在同名重载，不能确定配置对应对象："+choice.source());matched.put(choice,matches.getFirst());
                }
                connections.setSelectedItem(destination.element.getDataSource());destinations.setSelectedItem(destination);
                recoverable.setSelected(settings.recoverable());verifyContent.setSelected(settings.verifyContent());records.setSelected(settings.records());foreignKeys.setSelected(settings.foreignKeys());keepGoing.setSelected(settings.continueOnError());existingPolicy.setSelectedItem(settings.policy());rows.forEach(r->r.checked=false);
                matched.forEach((choice,row)->{row.checked=true;row.name=choice.target();row.custom=choice.custom();row.data=choice.data();row.mappings=new LinkedHashMap<>(choice.mappings());row.objectSql=choice.objectSql();row.dependencies=new LinkedHashSet<>(choice.dependencies());});objects.refreshChoices();plan=null;acknowledge.setSelected(false);status.setText("已加载配置；下一步将重新生成并检查计划");
            }
        }catch(Exception ex){Messages.showErrorDialog(project,ex.getMessage(),save?"保存配置失败":"加载配置失败");}
    }
    private void selectDependencies(){
        Set<String> names=new HashSet<>();rows.stream().filter(r->r.checked).forEach(r->names.add(r.source.getName()));boolean changed;
        do{changed=false;for(Row row:rows)if(names.contains(row.source.getName())){
            if(row.source.getDelegate() instanceof com.intellij.database.model.DasTable table&&foreignKeys.isSelected())for(var key:DasUtil.getForeignKeys(table)){
                if(Objects.equals(key.getRefTableSchema(),sourceNs.getName())||key.getRefTableSchema()==null||key.getRefTableSchema().isBlank())changed|=names.add(key.getRefTableName());
            }
            if(DbSelection.engine(sourceNs)==Engine.POSTGRES)for(var col:DasUtil.getColumns(row.source)){var ref=PostgresSequences.reference(col.getDefault(),sourceNs.getName());if(ref!=null&&ref.schema().equals(sourceNs.getName()))changed|=names.add(ref.name());}
        }}while(changed);
        Set<String> available=new HashSet<>();rows.forEach(r->available.add(r.source.getName()));Set<String> missing=new HashSet<>(names);missing.removeAll(available);objects.selectNames(names);status.setText(missing.isEmpty()?"已补选当前模式中已加载的外键/序列依赖；函数体与视图依赖请人工核对。":"依赖未加载，请先内省："+String.join(", ",missing));
    }
    private void selectionChanged(){if(objects==null)return;selectionCount.setText(UiText.text("已选择 ")+objects.selectedCount()+UiText.text(" 个对象"));if(step==1){String error=objects.validation();status.setText(error==null?" ":error);next.setEnabled(!running&&!building&&error==null);}}
    private JPanel summaryPage(){
        JPanel p=new JPanel(new BorderLayout(0,12));JTabbedPane tabs=new JTabbedPane();tabs.addTab(UiText.text("摘要"),new JBScrollPane(new JBTable(summaryModel)));sql.setEditable(false);sql.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));tabs.addTab(UiText.text("执行 SQL"),new JBScrollPane(sql));warnings.setEditable(false);warnings.setLineWrap(true);warnings.setWrapStyleWord(true);tabs.addTab(UiText.text("转换 / 依赖说明"),new JBScrollPane(warnings));diff.setEditable(false);diff.setLineWrap(true);diff.setWrapStyleWord(true);diff.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));JPanel diffPanel=new JPanel(new BorderLayout(0,8));diffPanel.add(new JBScrollPane(diff));diffPanel.add(previewDiff,BorderLayout.SOUTH);tabs.addTab(UiText.text("同步差异"),diffPanel);previewDiff.addActionListener(e->previewDifferences());p.add(tabs);
        JPanel options=new JPanel(new GridLayout(0,1));options.add(keepGoing);options.add(acknowledge);options.add(new JLabel(UiText.text("清空使用 DELETE；重建会 DROP TABLE。已提交对象不自动回滚。")));p.add(options,BorderLayout.SOUTH);
        acknowledge.addActionListener(e->{if(step==2)next.setEnabled(!acknowledge.isVisible()||acknowledge.isSelected());});return p;
    }
    private JPanel resultPage(){
        JPanel p=new JPanel(new BorderLayout(0,10));p.add(resultSummary,BorderLayout.NORTH);JTabbedPane tabs=new JTabbedPane();tabs.addTab(UiText.text("对象结果"),new JBScrollPane(new JBTable(resultModel)));tabs.addTab(UiText.text("详细日志"),new JBScrollPane(logs));p.add(tabs);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));actions.add(cancelTransfer);actions.add(exportReport);exportReport.setEnabled(false);cancelTransfer.addActionListener(e->{if(transferControl!=null){transferControl.cancel();cancelTransfer.setEnabled(false);resultSummary.setText(UiText.text("正在取消，等待数据库响应…"));}});exportReport.addActionListener(e->exportResult());p.add(actions,BorderLayout.SOUTH);return p;
    }
    private void showReport(TransferReport.Snapshot report){if(isDisposed())return;latestReport=report;resultSummary.setText(report.summary());resultModel.setRowCount(0);for(var e:report.entries())resultModel.addRow(new Object[]{e.object().target(),UiText.text(e.state().label),e.rows()<0?"—":e.rows(),String.format(Locale.ROOT,"%.1f s",e.elapsedMillis()/1000.0),e.committed()?UiText.text("已提交"):UiText.text("未确认"),e.detail()});exportReport.setEnabled(report.finished());}
    private void exportResult(){if(latestReport==null)return;JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("tablebridge-result.txt"));if(chooser.showSaveDialog(getContentPane())!=JFileChooser.APPROVE_OPTION)return;var file=chooser.getSelectedFile();if(file.exists()&&JOptionPane.showConfirmDialog(getContentPane(),"覆盖已有文件？","导出结果",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;try{Files.writeString(file.toPath(),latestReport.text()+"\n详细日志\n"+logs.getText(),StandardCharsets.UTF_8);}catch(Exception ex){Messages.showErrorDialog(project,ex.getMessage(),"导出失败");}}
    @Override protected JComponent createSouthPanel(){
        JPanel panel=new JPanel(new BorderLayout());panel.setBorder(JBUI.Borders.empty(10,16,8,16));selectionCount.setForeground(JBColor.GRAY);panel.add(selectionCount,BorderLayout.WEST);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,JBUI.scale(8),0));JButton cancel=new JButton(UiText.text("关闭"));cancel.addActionListener(e->doCancelAction());previous.addActionListener(e->showStep(step-1));next.addActionListener(e->advance());actions.add(cancel);actions.add(previous);actions.add(next);panel.add(actions,BorderLayout.EAST);return panel;
    }
    private void advance(){
        if(running||building)return;
        if(step==0){showStep(1);return;}
        if(step==1){prepare();return;}
        if(step==2){
            if(acknowledge.isVisible()&&!acknowledge.isSelected())return;
            // Preserve reviewed recovery identity and checkpoints; do not regenerate the plan here.
            running=true;showStep(3);logs.setText("");status.setText(UiText.text("正在后台传输；可通过 IDE 后台任务进度取消。"));
            cancelTransfer.setEnabled(true);exportReport.setEnabled(false);
            transferControl=TransferRunner.start(project,plan,line->ApplicationManager.getApplication().invokeLater(()->{if(!isDisposed())logs.append(line+"\n");},ModalityState.any()),
                report->ApplicationManager.getApplication().invokeLater(()->showReport(report),ModalityState.any()),
                report->{running=false;if(!isDisposed()){showReport(report);cancelTransfer.setEnabled(false);next.setText(UiText.text("传输已结束"));next.setEnabled(false);status.setText(UiText.text("查看逐对象结果；再次传输请重新复制并生成计划。"));}});
        }
    }
    private void previewDifferences(){
        if(running||building||plan==null||plan.resume)return;
        TransferPlan reviewed=plan;building=true;next.setEnabled(false);previous.setEnabled(false);previewDiff.setEnabled(false);acknowledge.setSelected(false);
        new Task.Modal(project,UiText.text("读取同步差异"),true){
            List<SyncPreview.Table> result;String error;
            public void run(ProgressIndicator indicator){try{result=SyncPreview.read(project,reviewed,indicator);}catch(ProcessCanceledException ex){throw ex;}catch(Exception ex){error=ex.getMessage();}}
            private void finish(String failure){building=false;if(isDisposed())return;reviewed.syncPreview=result==null?List.of():result;diff.setText(result==null?UiText.text("差异预览不可用：")+failure+"\n"+UiText.text("可刷新预览，或确认后继续原有同步；未成功预览时不启用过期检查。"):SyncPreview.text(result));diff.setCaretPosition(0);previewDiff.setEnabled(true);previous.setEnabled(true);next.setEnabled(!acknowledge.isVisible()||acknowledge.isSelected());}
            public void onSuccess(){finish(error);}
            public void onCancel(){finish(UiText.text("用户取消"));}
            public void onThrowable(Throwable error){finish(error.getMessage());}
        }.queue();
    }
    private void restoreJob(){
        Destination destination=(Destination)destinations.getSelectedItem();if(destination==null)return;
        javax.swing.JFileChooser chooser=new javax.swing.JFileChooser(JobManifest.directory().toFile());
        if(chooser.showOpenDialog(objects)!=javax.swing.JFileChooser.APPROVE_OPTION)return;
        try{TransferPlan restored=ReadAction.compute(()->{try{return JobManifest.load(chooser.getSelectedFile().toPath(),sourceNs,destination.element,rows.stream().map(r->r.source).toList());}catch(java.io.IOException ex){throw new IllegalArgumentException(ex.getMessage(),ex);}});finishPrepare(restored,null);}catch(Exception ex){status.setText("恢复计划无法加载："+ex.getMessage());}
    }
    private void prepare(){
        if(objects.validation()!=null){status.setText(objects.validation());return;}
        var choices=rows.stream().filter(r->r.checked).map(r->new PlanBuilder.Choice(r.source,r.target(),r.copyData(records.isSelected()),r.custom?r.mappings:Map.of(),r.custom?r.objectSql:"",r.custom?r.dependencies:Set.of())).toList();
        Destination destination=(Destination)destinations.getSelectedItem();if(destination==null)return;
        ExistingPolicy policy=(ExistingPolicy)existingPolicy.getSelectedItem();boolean fk=foreignKeys.isSelected(),verify=verifyContent.isSelected(),durable=recoverable.isSelected();building=true;next.setEnabled(false);previous.setEnabled(false);status.setForeground(JBColor.GRAY);status.setText(UiText.text("正在生成和检查 SQL…"));
        new Task.Modal(project,"TableBridge 生成传输计划",true){
            TransferPlan result;String error;
            @Override public void run(ProgressIndicator indicator){try{result=ReadAction.compute(()->PlanBuilder.build(project,choices,destination.element,false,fk,policy));result.verifyContent=verify;result.recoverable|=durable;if(result.recoverable)JobManifest.freezeKeys(result);if(result.items.stream().anyMatch(SyncPreview::sync)){try{result.syncPreview=SyncPreview.read(project,result,indicator);}catch(ProcessCanceledException ex){throw ex;}catch(Exception ex){error=UiText.text("差异预览不可用：")+ex.getMessage();}}}catch(ProcessCanceledException ex){throw ex;}catch(Exception ex){error=ex.getMessage();}}
            @Override public void onSuccess(){finishPrepare(result,error);}
            @Override public void onCancel(){finishPrepare(null,"已取消计划生成");}
            @Override public void onThrowable(Throwable ex){finishPrepare(null,ex.getMessage());}
        }.queue();
    }
    private void finishPrepare(TransferPlan result,String error){
        building=false;if(isDisposed())return;previous.setEnabled(true);next.setEnabled(true);if(result==null){status.setForeground(JBColor.RED);status.setText(UiText.text("无法继续：")+(error==null?UiText.text("计划生成失败"):error));status.setToolTipText(status.getText());ApplicationManager.getApplication().invokeLater(()->{if(!isDisposed())next.requestFocusInWindow();},ModalityState.any());return;}
        status.setForeground(JBColor.GRAY);status.setToolTipText(null);plan=result;previewDiff.setEnabled(!plan.resume&&plan.items.stream().anyMatch(SyncPreview::sync));diff.setText(!plan.syncPreview.isEmpty()?SyncPreview.text(plan.syncPreview):error!=null?error+"\n"+UiText.text("可刷新预览，或确认后继续原有同步；未成功预览时不启用过期检查。"):plan.resume?UiText.text("恢复任务使用已保存的暂存快照，本次不生成新的差异预览。"):UiText.text("选择增量或镜像同步后，在此查看差异预览。"));diff.setCaretPosition(0);summaryModel.setRowCount(0);for(var i:plan.items)summaryModel.addRow(new Object[]{UiText.text(DbSelection.group(i.source())),i.source().getName(),i.targetName(),i.action()==ExistingPolicy.APPEND||i.action()==ExistingPolicy.CLEAR?UiText.text("仅数据"):i.data()?UiText.text("结构和数据"):DbSelection.group(i.source()).equals("表")?UiText.text("仅结构"):UiText.text("对象定义"),i.action()==ExistingPolicy.FAIL?UiText.text("新建"):UiText.text(i.action().label)});warnings.setText(String.join("\n",plan.warnings.stream().map(UiText::text).toList()));acknowledge.setSelected(false);acknowledge.setVisible(plan.recoverable||plan.destructive()||plan.engine!=plan.targetEngine);sql.setText(plan.preview());sql.setCaretPosition(0);showStep(2);status.setText(plan.items.size()+UiText.text(" 个对象；")+(plan.destructive()?UiText.text("包含清空或删除操作，请核对摘要。"):plan.engine!=plan.targetEngine?UiText.text("请查看转换说明后确认。"):UiText.text("请核对摘要与 SQL。")));
    }
    private void showStep(int value){
        step=value;((CardLayout)pages.getLayout()).show(pages,Integer.toString(value));
        for(int i=0;i<steps.length;i++){steps[i].setForeground(i==Math.min(value,2)?new JBColor(0x3474ED,0x6E9EFF):JBColor.GRAY);steps[i].setFont(steps[i].getFont().deriveFont(i==Math.min(value,2)?Font.BOLD:Font.PLAIN));}
        direction.setVisible(value>0);previous.setVisible(value>0&&value<3);next.setText(value==2?UiText.text("开始传输"):value==3?UiText.text("传输中…"):UiText.text("下一步"));next.setEnabled(!running&&!building);status.setText(" ");updateDirection();selectionChanged();if(value==2)next.setEnabled(!acknowledge.isVisible()||acknowledge.isSelected());if(value==0)selectionCount.setText(sourceNs.getDataSource().getDbms().getDisplayName()+" → "+(((Destination)destinations.getSelectedItem()).element.getDataSource().getDbms().getDisplayName()));
    }
    private void updateDirection(){var target=(Destination)destinations.getSelectedItem();direction.setText(DbSelection.path(sourceNs)+"   →   "+(target==null?"":target));direction.setToolTipText(direction.getText());if(step==0&&target!=null)selectionCount.setText(sourceNs.getDataSource().getDbms().getDisplayName()+" → "+target.element.getDataSource().getDbms().getDisplayName());}
}
