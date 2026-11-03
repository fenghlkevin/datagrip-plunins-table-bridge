package com.tablebridge;

import java.util.*;

/** Records committed data separately from fully completed objects. Worker-owned mutable state. */
final class TransferReport {
    enum State {
        WAITING("等待"), CREATING("创建结构"), COPYING("复制数据"), VERIFYING("校验数据"),
        PENDING_DDL("等待索引/约束"), FINISHING("创建索引/约束"), SUCCESS("成功"),
        FAILED("失败"), CANCELLED("已取消"), INCOMPLETE("未完成"), SKIPPED("未执行");
        final String label;State(String label){this.label=label;}
        boolean active(){return this==CREATING||this==COPYING||this==VERIFYING||this==FINISHING;}
    }
    record ObjectInfo(String source,String target,String kind){}
    record Entry(ObjectInfo object,State state,long rows,long elapsedMillis,boolean committed,String detail){}
    record Snapshot(List<Entry> entries,boolean finished,boolean cancelled,long elapsedMillis,List<String> messages){
        long count(State state){return entries.stream().filter(e->e.state==state).count();}
        String summary(){return (finished?(cancelled?UiText.text("已取消"):UiText.text("执行结束")):UiText.text("正在传输"))+UiText.text(" · 成功 ")+count(State.SUCCESS)+" / "+entries.size()+UiText.text(" · 失败 ")+count(State.FAILED)+UiText.text(" · 未完成 ")+(count(State.INCOMPLETE)+count(State.CANCELLED))+UiText.text(" · 未执行 ")+count(State.SKIPPED);}
        String text(){StringBuilder out=new StringBuilder(summary()).append('\n');out.append(UiText.text("总耗时：")).append(elapsedMillis).append(" ms\n");
            for(Entry e:entries)out.append(e.object.kind).append(" | ").append(e.object.source).append(" → ").append(e.object.target).append(" | ").append(UiText.text(e.state.label)).append(UiText.text(" | 已处理行数：")).append(e.rows<0?"—":e.rows).append(" | ").append(e.elapsedMillis).append(" ms | ").append(e.committed?"已提交":UiText.text("未确认提交")).append(" | ").append(e.detail).append('\n');
            messages.forEach(m->out.append(m).append('\n'));return out.toString();}
    }
    private static final class Mutable {
        final ObjectInfo object;State state=State.WAITING;long rows=-1,started,ended;boolean committed;String detail="";
        Mutable(ObjectInfo object){this.object=object;}
    }
    private final List<Mutable> entries;
    private final List<String> messages=new ArrayList<>();
    private final long started=System.nanoTime();
    private long ended;private boolean finished,cancelled;
    TransferReport(List<ObjectInfo> objects){entries=objects.stream().map(Mutable::new).toList();}
    void stage(int index,State state){Mutable e=entries.get(index);if(e.started==0)e.started=System.nanoTime();e.state=state;}
    void rows(int index,long rows){entries.get(index).rows=rows;}
    void committed(int index,boolean needsDdl){Mutable e=entries.get(index);e.committed=true;if(needsDdl)e.state=State.PENDING_DDL;else success(index);}
    void success(int index){Mutable e=entries.get(index);e.state=State.SUCCESS;e.ended=System.nanoTime();e.detail="";}
    void fail(int index,String detail){Mutable e=entries.get(index);e.detail=UiText.text(e.state.label)+UiText.text("：")+detail;e.state=State.FAILED;e.ended=System.nanoTime();}
    void message(String message){messages.add(message);}
    void finish(boolean cancelled,String reason){if(finished)return;this.cancelled=cancelled;finished=true;ended=System.nanoTime();if(reason!=null&&!reason.isBlank())messages.add(reason);
        for(Mutable e:entries){if(e.state.active()){e.detail=UiText.text(e.state.label)+UiText.text("：")+(cancelled?UiText.text("用户取消"):reason==null?UiText.text("执行中断"):reason);e.state=cancelled?State.CANCELLED:State.FAILED;}
            else if(e.state==State.WAITING){e.state=State.SKIPPED;e.detail=UiText.text("前序任务停止，未开始");}
            else if(e.state==State.PENDING_DDL){e.state=State.INCOMPLETE;e.detail=UiText.text("数据/主体定义已提交，后置索引或约束尚未完成");}
            if(e.ended==0)e.ended=ended;
        }
    }
    Snapshot snapshot(){long now=finished?ended:System.nanoTime();return new Snapshot(entries.stream().map(e->new Entry(e.object,e.state,e.rows,e.started==0?0:((e.ended==0?now:e.ended)-e.started)/1_000_000,e.committed,e.detail)).toList(),finished,cancelled,(now-started)/1_000_000,List.copyOf(messages));}
}
