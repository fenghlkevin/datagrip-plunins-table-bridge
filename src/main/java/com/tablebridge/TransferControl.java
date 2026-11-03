package com.tablebridge;

import com.intellij.openapi.progress.ProgressIndicator;
import java.lang.reflect.*;
import java.sql.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Non-blocking UI cancellation, propagated to active JDBC statements and the IDE task. */
final class TransferControl {
    private final AtomicBoolean requested=new AtomicBoolean();
    private final AtomicReference<ProgressIndicator> indicator=new AtomicReference<>();
    private final Set<Statement> statements=ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService watcher;
    void attach(ProgressIndicator value){indicator.set(value);if(requested.get())value.cancel();watcher=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"TableBridge task cancellation");t.setDaemon(true);return t;});watcher.scheduleWithFixedDelay(()->{if(value.isCanceled())cancel();},200,200,TimeUnit.MILLISECONDS);}
    void cancel(){if(!requested.compareAndSet(false,true))return;ProgressIndicator p=indicator.get();if(p!=null)p.cancel();
        for(Statement s:statements)CompletableFuture.runAsync(()->{try{s.cancel();}catch(SQLException ignored){}});
    }
    boolean cancelled(){return requested.get()||(indicator.get()!=null&&indicator.get().isCanceled());}
    void detach(){if(watcher!=null)watcher.shutdownNow();indicator.set(null);statements.clear();}
    Connection wrap(Connection connection){return (Connection)Proxy.newProxyInstance(TransferControl.class.getClassLoader(),connection instanceof RemoteBulk.ConnectionApi?new Class<?>[]{Connection.class,RemoteBulk.ConnectionApi.class}:new Class<?>[]{Connection.class},(p,m,args)->{
        Object value=invoke(connection,m,args);if(value instanceof Statement s)return wrapStatement(s);return value;
    });}
    private Object wrapStatement(Statement statement){statements.add(statement);Class<?> type=statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
        return Proxy.newProxyInstance(TransferControl.class.getClassLoader(),new Class<?>[]{type},(p,m,args)->{
            if(m.getName().startsWith("execute")&&cancelled()){
                boolean cleanup=args!=null&&args.length>0&&args[0] instanceof String sql&&sql.startsWith("SET IDENTITY_INSERT ")&&sql.endsWith(" OFF");
                if(!cleanup)throw new com.intellij.openapi.progress.ProcessCanceledException();
            }
            try{return invoke(statement,m,args);}finally{if(m.getName().equals("close"))statements.remove(statement);}
        });
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
}
