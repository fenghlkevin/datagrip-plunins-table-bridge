package com.tablebridge;

import java.sql.*;
import java.util.*;
import java.math.BigDecimal;

final class SyncPreviewTest {
    static void run()throws Exception{
        List<String> fields=List.of("tenant","id","note","amount","payload"),keys=List.of("tenant","id");
        List<Integer> types=List.of(Types.VARCHAR,Types.INTEGER,Types.VARCHAR,Types.NUMERIC,Types.VARBINARY);
        List<Object[]> source=List.of(new Object[]{"a",1,"中文\nnew",new BigDecimal("1.00"),new byte[]{1,2}},new Object[]{"a",2,null,new BigDecimal("2"),null},new Object[]{"b",1,"same",new BigDecimal("3.00"),null});
        List<Object[]> target=List.of(new Object[]{"a",1,"old",new BigDecimal("1.0"),new byte[]{1,3}},new Object[]{"b",1,"same",new BigDecimal("3"),null},new Object[]{"a",3,"extra",null,null});
        var left=SyncPreview.scan(connection(source,types),Engine.POSTGRES,"s.t",fields,keys,types,()->{});
        var right=SyncPreview.scan(connection(target,types),Engine.POSTGRES,"d.t",fields,keys,types,()->{});
        CoreTest.check(left.digest().equals(RowDigest.read(connection(source,types),Engine.POSTGRES,"s.t",fields,types,()->{})),"preview fingerprint agrees with execution digest");
        var upsert=SyncPreview.compare(0,"t",fields,keys,types,ExistingPolicy.UPSERT,left,right);
        CoreTest.check(upsert.inserts()==1&&upsert.updates()==1&&upsert.deletes()==0&&upsert.retained()==1,"composite-key upsert counts preserve destination-only rows");
        var mirror=SyncPreview.compare(0,"t",fields,keys,types,ExistingPolicy.MIRROR,left,right);
        CoreTest.check(mirror.deletes()==1&&mirror.retained()==0,"mirror counts destination-only deletion");
        CoreTest.check(upsert.samples().getFirst().changes().size()==2,"normalized equal decimals excluded from changed fields");
        CoreTest.check(SyncPreview.text(List.of(upsert)).contains("0x0103")&&SyncPreview.text(List.of(upsert)).contains("\\n"),"binary and newline sample values render safely");
        CoreTest.check(SyncPreview.compare(0,"t",fields,keys,types,ExistingPolicy.MIRROR,left,left).updates()==0,"identical snapshots have no changes");
        try{SyncPreview.require(left.digest(),right.digest(),"t");throw new AssertionError("stale preview accepted");}catch(SQLException expected){CoreTest.check(expected.getMessage().contains("预览已过期"),"stale preview explicitly rejected");}
        SyncPreview.require(left.digest(),left.digest(),"t");
        List<Object[]> repeated=new ArrayList<>();for(int i=0;i<30;i++)repeated.add(new Object[]{"a",i,null,null,null});
        var many=SyncPreview.scan(connection(repeated,types),Engine.POSTGRES,"s.t",fields,keys,types,()->{});
        var empty=SyncPreview.scan(connection(List.of(),types),Engine.POSTGRES,"d.t",fields,keys,types,()->{});
        var limited=SyncPreview.compare(0,"t",fields,keys,types,ExistingPolicy.MIRROR,many,empty);
        CoreTest.check(limited.inserts()==30&&limited.samples().size()==20,"sample cap never truncates counts");
        try{SyncPreview.scan(connection(Arrays.asList(source.getFirst(),source.getFirst()),types),Engine.POSTGRES,"s.t",fields,keys,types,()->{});throw new AssertionError("duplicate accepted");}catch(SQLException expected){CoreTest.check(true,"duplicate converted primary keys rejected");}
        try{SyncPreview.scan(connection(Collections.singletonList(new Object[]{null,1,null,null,null}),types),Engine.POSTGRES,"s.t",fields,keys,types,()->{});throw new AssertionError("NULL key accepted");}catch(SQLException expected){CoreTest.check(true,"NULL primary keys rejected");}
        List<Object[]> large=new AbstractList<>(){public int size(){return SyncPreview.MAX_ROWS+1;}public Object[] get(int i){return new Object[]{"a",i,null,null,null};}};
        try{SyncPreview.scan(connection(large,types),Engine.POSTGRES,"s.t",fields,keys,types,()->{});throw new AssertionError("row limit ignored");}catch(SQLException expected){CoreTest.check(expected.getMessage().contains("50000"),"row cap rejects incomplete preview");}
        try{SyncPreview.scan(connection(Collections.singletonList(new Object[]{"a",1,null,null,new byte[SyncPreview.MAX_BYTES+1]}),types),Engine.POSTGRES,"s.t",fields,keys,types,()->{});throw new AssertionError("content cap ignored");}catch(SQLException expected){CoreTest.check(expected.getMessage().contains("32 MiB"),"content cap rejects incomplete preview");}
        try{SyncPreview.scan(connection(source,types),Engine.POSTGRES,"s.t",fields,keys,types,()->{throw new IllegalStateException("cancel");});throw new AssertionError("cancel ignored");}catch(IllegalStateException expected){CoreTest.check(true,"preview scan responds to cancellation");}
    }
    static Connection connection(List<Object[]> data,List<Integer> types){
        return CoreTest.proxy(Connection.class,(p,m,a)->{
            if(!m.getName().equals("createStatement"))return CoreTest.zero(m.getReturnType());
            return CoreTest.proxy(Statement.class,(q,n,b)->{
                if(!n.getName().equals("executeQuery"))return CoreTest.zero(n.getReturnType());
                int[] index={-1};return CoreTest.proxy(ResultSet.class,(r,k,c)->switch(k.getName()){
                    case "next"->++index[0]<data.size();
                    case "getMetaData"->CoreTest.meta(types.stream().mapToInt(Integer::intValue).toArray());
                    case "getBigDecimal"->{Object v=data.get(index[0])[(int)c[0]-1];yield v==null?null:v instanceof BigDecimal?v:new BigDecimal(v.toString());}
                    case "getBytes"->data.get(index[0])[(int)c[0]-1];
                    case "getString"->{Object v=data.get(index[0])[(int)c[0]-1];yield v==null?null:v.toString();}
                    default->CoreTest.zero(k.getReturnType());
                });
            });
        });
    }
}
