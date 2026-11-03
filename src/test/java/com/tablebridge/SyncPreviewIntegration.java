package com.tablebridge;

import java.sql.*;
import java.util.*;

/** Runs only inside CrossIntegration's verified, disposable tb_matrix schemas. */
final class SyncPreviewIntegration {
    static void run(Connection source,Connection target,Engine from,Engine to,boolean remote,String suffix)throws Exception{
        String left=from.qualified("tb_matrix","preview_source_"+suffix),right=to.qualified("tb_matrix","preview_target_"+suffix);
        String decimal=from==Engine.MYSQL?"decimal(12,3)":"numeric(12,3)",binary=from==Engine.MYSQL?"varbinary(10)":"bytea";
        RowCopier.execute(source,"CREATE TABLE "+left+"(tenant varchar(20),id integer,note varchar(80),amount "+decimal+",payload "+binary+",PRIMARY KEY(tenant,id))");
        RowCopier.execute(target,"CREATE TABLE "+right+"(tenant varchar(20),id integer,note varchar(80),amount "+(to==Engine.MYSQL?"decimal(12,3)":"numeric(12,3)")+",payload "+(to==Engine.MYSQL?"varbinary(10)":"bytea")+",PRIMARY KEY(tenant,id))");
        RowCopier.execute(source,"INSERT INTO "+left+" VALUES('a',1,'中文 new',1.000,NULL),('a',2,NULL,2,NULL),('b',1,'same',3,NULL)");
        RowCopier.execute(target,"INSERT INTO "+right+" VALUES('a',1,'old',1,NULL),('a',3,'extra',NULL,NULL),('b',1,'same',3.000,NULL)");
        Connection read=remote?CrossIntegration.remote(source,from):source,write=remote?CrossIntegration.remote(target,to):target;
        var fields=List.of("tenant","id","note","amount","payload");var keys=List.of("tenant","id");var types=List.of(Types.VARCHAR,Types.INTEGER,Types.VARCHAR,Types.NUMERIC,Types.VARBINARY);
        var a=SyncPreview.scan(read,from,left,fields,keys,types,()->{});var b=SyncPreview.scan(write,to,right,fields,keys,types,()->{});
        var preview=SyncPreview.compare(0,right,fields,keys,types,ExistingPolicy.MIRROR,a,b);
        CrossIntegration.check(preview.inserts()==1&&preview.updates()==1&&preview.deletes()==1,"real/native cross-engine composite-key preview counts "+suffix);
        CrossIntegration.check(a.digest().equals(RowDigest.read(read,from,left,fields,types,()->{})),"preview source matches execution fingerprint "+suffix);
        CrossIntegration.check(b.digest().equals(RowDigest.read(write,to,right,fields,types,()->{})),"preview target matches execution fingerprint "+suffix);
        CrossIntegration.check(TargetValidation.count(target,right,to)==3,"read-only preview leaves target rows intact "+suffix);
        RowCopier.execute(target,"UPDATE "+right+" SET note='changed after preview' WHERE tenant='b'");
        boolean stale=false;try{SyncPreview.require(preview.target(),RowDigest.read(write,to,right,fields,types,()->{}),right);}catch(SQLException expected){stale=true;}
        CrossIntegration.check(stale,"target mutation invalidates preview "+suffix);
        RowCopier.execute(target,"UPDATE "+right+" SET note='same' WHERE tenant='b'");
        RowCopier.execute(source,"UPDATE "+left+" SET note='source changed after preview' WHERE tenant='b'");
        stale=false;try{SyncPreview.require(preview.source(),RowDigest.read(read,from,left,fields,types,()->{}),left);}catch(SQLException expected){stale=true;}
        CrossIntegration.check(stale,"source mutation invalidates preview "+suffix);
        RowCopier.execute(source,"UPDATE "+left+" SET note='same' WHERE tenant='b'");
        target.setAutoCommit(false);
        try{
            if(to==Engine.MYSQL)target.setCatalog("tb_matrix");
            var job=new ResumableTable(write,to,"tb_matrix",UUID.randomUUID().toString(),false,"preview",()->{},line->{});
            var spec=new ResumableTable.Spec(left,right,fields,keys,types,false,ExistingPolicy.MIRROR);
            job.load(0,read,from,spec,n->{});
            SyncPreview.require(preview.source(),RowDigest.read(write,to,job.stage(0),fields,types,()->{}),right);
            SyncPreview.require(preview.target(),RowDigest.read(write,to,right,fields,types,()->{}),right);
            CrossIntegration.check(job.apply(0,spec,true)==3,"actual mirror DML agrees with preview counts "+suffix);
            job.verify(0,spec);target.commit();job.cleanup(List.of(0));
            CrossIntegration.check(RowDigest.read(write,to,right,fields,types,()->{}).equals(preview.source()),"applied mirror contents agree with preview snapshot "+suffix);
        }finally{target.rollback();target.setAutoCommit(true);}
    }
}
