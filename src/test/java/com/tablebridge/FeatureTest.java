package com.tablebridge;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static com.tablebridge.CoreTest.*;

final class FeatureTest {
    static CrossEngineDdl.Column column(String name,String type,int jdbc,int size,int scale,boolean identity,String def){return new CrossEngineDdl.Column(name,type,jdbc,size,scale,true,identity,def,false);}
    static void run()throws Exception{
        var oraDefault=CrossEngineDdl.build(Engine.POSTGRES,Engine.ORACLE,"T","t",List.of(column("created","timestamp",Types.TIMESTAMP,0,0,false,"now()")),List.of());
        check(oraDefault.sql().contains("DEFAULT LOCALTIMESTAMP(6) NOT NULL"),"Oracle default clause precedes NOT NULL constraint");
        check(SqlNames.renameStructureHeader("CREATE UNIQUE INDEX ix ON dst.t(id)",Engine.POSTGRES,Map.of("ix","ix_copy")).startsWith("CREATE UNIQUE INDEX \"ix_copy\""),"unqualified PG index header renamed without modifying fields");
        check(CrossStructure.portableIndex(Engine.MYSQL,"dst","t",new CrossStructure.Index("expr",List.of("lower(label)"),List.of(false),false),null,Set.of("label")).contains("((lower(`label`)))"),"automatic simple function index uses MySQL functional key parentheses");
        check(CrossStructure.portableIndex(Engine.POSTGRES,"dst","t",new CrossStructure.Index("part",List.of("id"),List.of(true),false),"id > 0",Set.of("id")).endsWith("WHERE \"id\" > 0"),"partial index predicate qualified and retained");
        fails(()->CrossStructure.portableIndex(Engine.MYSQL,"dst","t",new CrossStructure.Index("part",List.of("id"),List.of(false),false),"id > 0",Set.of("id")),"MySQL partial index requires explicit manual strategy");
        fails(()->CrossStructure.portableIndex(Engine.POSTGRES,"dst","t",new CrossStructure.Index("custom",List.of("custom_fn(id)"),List.of(false),false),null,Set.of("id")),"unknown indexed functions require manual review");
        String collision=SqlNames.rebase("CREATE INDEX s.orders ON s.orders(id)",Engine.ORACLE,"s","d",Map.of("orders","orders_copy"));
        check(collision.contains("INDEX \"d\".\"orders\"")&&collision.contains("ON \"d\".\"orders_copy\""),"index names equal to table names cannot redirect table references");
        check(SqlNames.renameStructureHeader(collision,Engine.ORACLE,Map.of("orders","orders_idx")).contains("INDEX \"d\".\"orders_idx\""),"qualified child header independently renamed");
        fails(()->TableStructureSql.requireAutomaticTriggerReferences("CREATE TRIGGER d.a AFTER INSERT ON d.t FOR EACH ROW INSERT INTO audit(id) VALUES(NEW.id)"),"unqualified trigger writes require explicit target review");
        TableStructureSql.requireAutomaticTriggerReferences("CREATE TRIGGER d.a AFTER INSERT ON d.t FOR EACH ROW INSERT INTO d.audit(id) VALUES(NEW.id)");
        check(new FieldMapping(Engine.POSTGRES,"timestamp(6) with time zone",false,false).jdbcType()==Types.TIMESTAMP_WITH_TIMEZONE,"explicit timezone mapping accepted");
        check(new FieldMapping(Engine.ORACLE,"timestamp(9) with local time zone",false,false).jdbcType()==Types.TIMESTAMP_WITH_TIMEZONE,"Oracle native nine-digit local timezone mapping accepted");
        check(ZonedValues.parse("2026-10-01 07:04:56.123456 +0:00").toInstant().equals(java.time.Instant.parse("2026-10-01T07:04:56.123456Z")),"Oracle JDBC single-digit UTC offset accepted");
        check(ZonedValues.parse("2026-10-01 12:34:56.123456 +5:30").toInstant().equals(java.time.Instant.parse("2026-10-01T07:04:56.123456Z")),"Oracle JDBC half-hour single-digit offset accepted");
        check(ZonedValues.parseTime("12:34:56.123456+02").toString().equals("12:34:56.123456+02:00"),"timetz offset and microseconds retained");
        check(ZonedValues.oracleText("2026-10-01T12:34:56.123456789 +02:00 Europe/Paris").endsWith("+02:00"),"Oracle offset and nine-digit fraction retained without conflicting timezone format");
        check(CrossEngineDdl.type(column("clock","timetz",Types.TIME_WITH_TIMEZONE,0,0,false,null),Engine.POSTGRES,Engine.MYSQL).jdbcType()==Types.VARCHAR,"cross timetz has explicit lossless text default");
        String structure="CREATE TABLE dst.t(id integer, label varchar(50) COLLATE \"C\")\n"+TableStructureSql.SEPARATOR+"\nCREATE INDEX expr ON dst.t(lower(label)) WHERE id > 0\n"+TableStructureSql.SEPARATOR+"\nCREATE TRIGGER audit BEFORE INSERT ON dst.t FOR EACH ROW EXECUTE FUNCTION dst.audit()";
        var definition=TableStructureSql.validate(Engine.POSTGRES,"dst","t",structure);
        check(definition.after().size()==2&&definition.create().contains("COLLATE"),"reviewed full structure retains collation partial expression index and trigger");
        check(CrossStructure.phase(definition.after().get(1))==2&&CrossStructure.phase(definition.after().get(0))==0,"triggers only after all indexes foreign keys and rows");
        check(TableStructureSql.validate(Engine.MYSQL,"dst","t","CREATE TABLE `dst`.`t`(id int)\n"+TableStructureSql.SEPARATOR+"\nCREATE TRIGGER `dst`.`audit` BEFORE INSERT ON `dst`.`t` FOR EACH ROW BEGIN SET NEW.id = NEW.id + 1; END;").after().size()==1,"MySQL trigger block semicolons remain one statement");
        check(TableStructureSql.validate(Engine.ORACLE,"DST","T","CREATE TABLE DST.T(ID NUMBER)\n"+TableStructureSql.SEPARATOR+"\nCREATE TRIGGER DST.AUDIT BEFORE INSERT ON DST.T FOR EACH ROW BEGIN :NEW.ID := :NEW.ID + 1; END;").after().size()==1,"Oracle trigger block retained");
        for(String invalid:List.of("CREATE INDEX ix ON other.t(id)","CREATE INDEX other.ix ON dst.t(id)","CREATE TRIGGER audit BEFORE INSERT ON other.t EXECUTE FUNCTION dst.f()","ALTER TABLE dst.t DROP COLUMN id","CREATE INDEX ix ON dst.t(id); DELETE FROM dst.t"))fails(()->TableStructureSql.validate(Engine.POSTGRES,"dst","t","CREATE TABLE dst.t(id int)\n"+TableStructureSql.SEPARATOR+"\n"+invalid),"child structures cannot target another table or append statements");
        fails(()->TableStructureSql.requirePortableSource("CREATE TABLE s.t(a text COLLATE \"C\")"),"cross collation never silently dropped");
        fails(()->TableStructureSql.requirePortableSource("CREATE INDEX ix ON s.t USING gin(a)"),"GIN never silently downgraded to btree");
        TableStructureSql.requirePortableSource("COMMENT ON TABLE s.t IS 'COLLATE GIN'");
        fails(()->CustomObjectSql.validate(Engine.POSTGRES,"Dst","V","VIEW","CREATE VIEW Dst.V AS SELECT 1"),"case-folded manual destination cannot differ from selected object");
        fails(()->CustomObjectSql.validate(Engine.POSTGRES,"dst","v","VIEW","CREATE VIEW `dst`.`v` AS SELECT 1"),"manual target identifier quote dialect checked");
        check(CustomObjectSql.validate(Engine.ORACLE,"DST","P","PROCEDURE","CREATE PROCEDURE DST.P IS BEGIN NULL; END P;").endsWith("END P;"),"Oracle named routine END supported");

        check(CustomObjectSql.validate(Engine.MYSQL,"dst","p","PROCEDURE","CREATE PROCEDURE `dst`.`p`() BEGIN SELECT 1; END;").contains("SELECT 1"),"manual procedure single target CREATE with body semicolon");
        for(String sql:List.of("CREATE OR REPLACE VIEW dst.v AS SELECT 1","CREATE VIEW other.v AS SELECT 1","CREATE VIEW dst.v AS SELECT 1; DROP TABLE dst.t","CREATE VIEW dst.v AS SELECT 1; CREATE VIEW dst.x AS SELECT 2"))fails(()->CustomObjectSql.validate(Engine.POSTGRES,"dst","v","VIEW",sql),"manual SQL destination and one-object validation");

        var routineNumber=new CrossEngineDdl.Column("n","numeric(10,2)",Types.NUMERIC,10,2,false,false,null,false);
        var routineArgs=List.of(new PortableRoutine.Argument("a",routineNumber));
        fails(()->PortableRoutine.create(Engine.POSTGRES,Engine.ORACLE,"dst","twice",routineArgs,routineNumber,"CREATE FUNCTION src.twice(a numeric) RETURNS numeric LANGUAGE sql AS $$SELECT a * 2$$"),"Oracle constrained formal parameters require manual conversion rather than invalid DDL");
        check(PortableRoutine.create(Engine.POSTGRES,Engine.MYSQL,"dst","twice",routineArgs,routineNumber,"CREATE FUNCTION src.twice(a numeric) RETURNS numeric LANGUAGE sql AS $$SELECT a * 2$$").contains("NO SQL RETURN `a`*2"),"PG scalar SQL function converted to MySQL");
        check(PortableRoutine.create(Engine.MYSQL,Engine.POSTGRES,"dst","twice",routineArgs,routineNumber,"CREATE FUNCTION twice(a DECIMAL(10,2)) RETURNS DECIMAL(10,2) BEGIN RETURN a * 2; END;").contains("SELECT \"a\"*2"),"MySQL scalar function body translated to PG");
        fails(()->PortableRoutine.create(Engine.POSTGRES,Engine.MYSQL,"dst","unsafe",routineArgs,routineNumber,"CREATE FUNCTION unsafe() RETURNS numeric LANGUAGE sql AS $$SELECT secret FROM business$$"),"routine relation access not silently translated");

        var attributes=List.of(new CrossEngineDdl.Column("amount","numeric(10,2)",Types.NUMERIC,10,2,false,false,null,false));
        check(PortableType.create(Engine.POSTGRES,Engine.ORACLE,"dst","money_record",attributes).equals("CREATE TYPE \"dst\".\"money_record\" AS OBJECT (\"amount\" number(10,2))"),"PG composite maps Oracle object attribute");
        fails(()->PortableType.create(Engine.POSTGRES,Engine.MYSQL,"dst","record",attributes),"no silent MySQL type coercion");
        fails(()->PortableType.create(Engine.POSTGRES,Engine.ORACLE,"dst","record",List.of()),"type metadata incomplete rejected");

        var sequenceSpec=new PortableSequence.Spec(java.math.BigInteger.valueOf(21),java.math.BigInteger.valueOf(2),java.math.BigInteger.ONE,java.math.BigInteger.valueOf(999),20,false);
        check(PortableSequence.create(Engine.POSTGRES,"dst","seq",sequenceSpec).contains("START WITH 21 CACHE 20 NO CYCLE"),"sequence next/increment/cache translated to PG");
        check(PortableSequence.create(Engine.ORACLE,"dst","seq",sequenceSpec).contains("START WITH 21 CACHE 20 NOCYCLE"),"sequence native Oracle syntax");
        fails(()->PortableSequence.create(Engine.MYSQL,"dst","seq",sequenceSpec),"MySQL sequence native absence explicit");
        fails(()->PortableSequence.create(Engine.POSTGRES,"dst","seq",new PortableSequence.Spec(java.math.BigInteger.ZERO,java.math.BigInteger.ZERO,java.math.BigInteger.ONE,java.math.BigInteger.TEN,1,false)),"invalid sequence bounds rejected");

        check(ZonedValues.parse("2026-10-01 12:34:56.123456+08").toInstant().equals(java.time.Instant.parse("2026-10-01T04:34:56.123456Z")),"offset timestamp parses PG hour offset without JVM zone");
        check(ZonedValues.timestamp("TIMESTAMP(6) WITH TIME ZONE"),"parameterized timestamp zone metadata detected");
        check(CrossEngineDdl.type(column("z","timestamptz",Types.TIMESTAMP,0,0,false,null),Engine.POSTGRES,Engine.MYSQL).jdbcType()==Types.VARCHAR,"zone-less MySQL default mapping preserves offset as explicit text");

        var wrappedView=PortableView.convert(Engine.POSTGRES,Engine.MYSQL,"src","dst","v","CREATE OR REPLACE VIEW src.v AS SELECT id, 12.50 AS price FROM src.users WHERE id >= 123;",Map.of("users","people"));
        check(wrappedView.sql().contains("12.50")&&wrappedView.sql().contains(">= 123")&&!wrappedView.sql().contains("REPLACE"),"CREATE wrapper rebuilt and numeric tokens retain full literals");
        var view=PortableView.convert(Engine.POSTGRES,Engine.MYSQL,"src","dst","v","SELECT users.id, 'src.users' AS note FROM src.users WHERE users.id > 0",Map.of("users","people"));
        check(view.sql().contains("FROM `dst`.`people` `users`")&&view.sql().contains("'src.users'")&&view.dependencies().equals(Set.of("users")),"view relation rename preserves qualifiers and literal text");
        check(PortableView.order(new LinkedHashMap<>(Map.of("outer",Set.of("inner"),"inner",Set.of("table")))).equals(List.of("inner","outer")),"view dependency ordered before dependant");
        boolean cycle=false;try{PortableView.order(Map.of("a",Set.of("b"),"b",Set.of("a")));}catch(IllegalArgumentException expected){cycle=true;}check(cycle,"cyclic view dependency blocked");
        for(String sql:List.of("SELECT * FROM other.users","SELECT * FROM users, secrets","SELECT now() FROM users","SELECT * FROM users; DROP TABLE x","SELECT * FROM missing","SELECT * FROM (SELECT * FROM users) q")){
            boolean rejected=false;try{PortableView.convert(Engine.POSTGRES,Engine.MYSQL,"src","dst","v",sql,Map.of("users","people"));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"nonportable view blocked: "+sql);
        }

        check(CrossStructure.check(Engine.MYSQL,"dst","t","positive","\"amount\" >= 0 AND code IN ('a','b')",Set.of("amount","code")).contains("CHECK (`amount` >= 0 AND `code` IN ('a','b'))"),"portable check identifiers converted without editing strings");
        for(String invalid:List.of("amount >= 0; DROP TABLE t","amount::integer > 0","unknown > 0","length(code)>0","amount > 0 -- hidden")){
            boolean rejected=false;try{CrossStructure.check(Engine.MYSQL,"dst","t","ck",invalid,Set.of("amount","code"));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"unsupported check rejected: "+invalid);
        }
        boolean emojiRejected=false;try{CrossStructure.literalComment("注释🙂");}catch(IllegalArgumentException expected){emojiRejected=true;}check(emojiRejected,"MySQL supplementary Unicode comment rejected before silent metadata loss");
        var annotated=CrossEngineDdl.build(Engine.POSTGRES,Engine.MYSQL,"dst","t",List.of(column("value","text",Types.VARCHAR,0,0,false,null)),List.of(),Map.of(),Map.of("value","说明：O'Brien"));
        check(annotated.sql().contains("COMMENT '说明：O''Brien'"),"inline MySQL column comment preserves Unicode and apostrophe");
        check(CrossStructure.comment(Engine.POSTGRES,"dst","t","value","O'Brien").equals("COMMENT ON COLUMN \"dst\".\"t\".\"value\" IS 'O''Brien'"),"PostgreSQL column comment qualified and escaped");
        boolean unsafeComment=false;try{CrossStructure.literalComment("a\\b");}catch(IllegalArgumentException expected){unsafeComment=true;}check(unsafeComment,"ambiguous MySQL backslash escaping rejected before execution");
        for(String name:List.of("bigint","int8","BIGINT"))check(CrossEngineDdl.type(column("id",name,Types.OTHER,19,0,false,null),Engine.POSTGRES,Engine.MYSQL).sql().equals("bigint"),"native bigint without JDBC metadata: "+name);
        check(CrossEngineDdl.type(column("id","bigint",0,19,0,false,null),Engine.MYSQL,Engine.POSTGRES).sql().equals("bigint"),"MySQL native bigint without JDBC metadata");
        check(CrossEngineDdl.type(column("v","character varying(50)",0,50,0,false,null),Engine.POSTGRES,Engine.MYSQL).sql().equals("varchar(50)"),"parameterized native varchar");
        fails(()->CrossEngineDdl.type(column("n","bigint[]",Types.BIGINT,0,0,false,null),Engine.POSTGRES,Engine.MYSQL),"arrays do not inherit scalar JDBC types");
        fails(()->CrossEngineDdl.type(column("n","custom.money",Types.NUMERIC,20,2,false,null),Engine.POSTGRES,Engine.MYSQL),"custom domain requires explicit mapping");
        for(String def:List.of("now()","CURRENT_TIMESTAMP","current_timestamp(6)","localtimestamp","transaction_timestamp()","((now()))")){
            var temporal=column("create_time","timestamp without time zone",Types.OTHER,0,0,false,def);
            check(CrossEngineDdl.defaultSql(temporal,Engine.MYSQL).equals(" DEFAULT CURRENT_TIMESTAMP(6)"),"microsecond default matches mapped datetime: "+def);
        }
        check(CrossEngineDdl.defaultSql(column("t","timestamp(3)",Types.OTHER,0,0,false,"now()"),Engine.MYSQL).equals(" DEFAULT (CAST(CURRENT_TIMESTAMP(6) AS DATETIME(3)))"),"different precision retained as expression");
        check(CrossEngineDdl.type(column("t","timestamp(0)",Types.OTHER,0,0,false,null),Engine.POSTGRES,Engine.MYSQL).sql().equals("datetime(0)"),"explicit zero precision preserved");
        check(CrossEngineDdl.defaultSql(column("t","timestamp",Types.OTHER,0,0,false,"'2026-09-30 12:34:56.123456'::timestamp without time zone"),Engine.MYSQL).equals(" DEFAULT '2026-09-30 12:34:56.123456'"),"timestamp literal cast converted");
        fails(()->CrossEngineDdl.defaultSql(column("t","timestamp",Types.OTHER,0,0,false,"clock_timestamp()"),Engine.MYSQL),"unsupported volatile default not silently changed");
        var mysql=List.of(column("id","bigint",Types.BIGINT,19,0,true,null),column("amount","decimal(20,4)",Types.DECIMAL,20,4,false,"0"),column("note","varchar(50)",Types.VARCHAR,50,0,false,"'a''b'"));
        var pg=CrossEngineDdl.build(Engine.MYSQL,Engine.POSTGRES,"public","order-copy",mysql,List.of("id"));
        check(pg.sql().contains("GENERATED BY DEFAULT AS IDENTITY"),"cross identity generated");check(pg.sql().contains("numeric(20,4)"),"cross decimals preserve precision");check(pg.sql().contains("\"order-copy\""),"cross quoted table");
        check(CrossEngineDdl.type(column("n","bigint unsigned",Types.BIGINT,20,0,false,null),Engine.MYSQL,Engine.POSTGRES).sql().equals("numeric(20,0)"),"unsigned range preserved");
        check(CrossEngineDdl.type(column("v","uuid",Types.OTHER,0,0,false,null),Engine.POSTGRES,Engine.MYSQL).sql().equals("char(36)"),"uuid mapped explicitly");
        check(CrossEngineDdl.type(column("j","json",Types.OTHER,0,0,false,null),Engine.MYSQL,Engine.POSTGRES).jdbcType()==Types.OTHER,"PG JSON uses OTHER binding");
        fails(()->CrossEngineDdl.type(column("n","numeric",Types.NUMERIC,0,0,false,null),Engine.POSTGRES,Engine.MYSQL),"unbounded numeric rejected");
        check(CrossEngineDdl.type(column("n","timestamp with time zone",Types.TIMESTAMP_WITH_TIMEZONE,0,0,false,null),Engine.POSTGRES,Engine.MYSQL).sql().equals("varchar(64)"),"timezone loss avoided through explicit text target");
        fails(()->CrossEngineDdl.type(column("n","time",Types.TIME,0,0,false,null),Engine.MYSQL,Engine.POSTGRES),"MySQL interval TIME not truncated");
        fails(()->CrossEngineDdl.defaultSql(column("n","int",Types.INTEGER,10,0,false,"dangerous()"),Engine.MYSQL),"unknown expressions rejected");
        fails(()->CrossEngineDdl.type(column("n","bigint unsigned",Types.BIGINT,20,0,true,null),Engine.MYSQL,Engine.POSTGRES),"out of range identity rejected");
        check(PostgresSequences.reference("nextval('public.t_id_seq'::regclass)","public").name().equals("t_id_seq"),"qualified sequence parsed");
        check(PostgresSequences.reference("nextval('\"Odd seq\"'::regclass)","public").name().equals("Odd seq"),"unqualified quoted sequence parsed");
        String longName=PostgresSequences.derivedName("中文表".repeat(20),"id");check(longName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=63,"sequence name UTF8 limit");
        check(SqlNames.rebase("CREATE TABLE public.t(id bigint DEFAULT nextval('t_id_seq'::regclass))",Engine.POSTGRES,"public","public",Map.of("t","copy","t_id_seq","copy_seq")).contains("'\"public\".\"copy_seq\"'::regclass"),"unqualified serial references remap");
        var info=List.of(new TransferReport.ObjectInfo("a","a2","表"),new TransferReport.ObjectInfo("b","b2","表"),new TransferReport.ObjectInfo("c","c2","表"));
        TransferReport report=new TransferReport(info);report.stage(0,TransferReport.State.COPYING);report.rows(0,42);report.committed(0,true);report.stage(1,TransferReport.State.CREATING);report.finish(true,null);var snapshot=report.snapshot();
        check(snapshot.count(TransferReport.State.SUCCESS)==0,"pending indexes never counted as success");check(snapshot.count(TransferReport.State.INCOMPLETE)==1&&snapshot.entries().getFirst().committed(),"committed partial preserved");check(snapshot.count(TransferReport.State.CANCELLED)==1&&snapshot.count(TransferReport.State.SKIPPED)==1,"cancel and skipped distinguished");
        TransferReport fail=new TransferReport(info);fail.stage(0,TransferReport.State.CREATING);fail.committed(0,true);fail.stage(0,TransferReport.State.FINISHING);fail.fail(0,"constraint failed");fail.finish(false,"stopped");check(fail.snapshot().entries().getFirst().committed()&&fail.snapshot().count(TransferReport.State.FAILED)==1,"post commit DDL error visible");
        TransferReport ok=new TransferReport(info.subList(0,1));ok.stage(0,TransferReport.State.COPYING);ok.committed(0,false);ok.finish(false,null);check(ok.snapshot().count(TransferReport.State.SUCCESS)==1,"no post DDL completes immediately");
        testConfiguration();
        testOracleMappings();
        check(CrossEngineDdl.type(column("payload","jsonb",Types.OTHER,0,0,false,null),Engine.POSTGRES,Engine.MYSQL).sql().equals("longtext"),"JSON numeric precision preserved by default text mapping");
        testCrossStructures();
        testFieldMappings();
        testBulkCancellation();
        testBulkRows();
        testControl();
    }
    static void testConfiguration()throws Exception{
        var directory=java.nio.file.Files.createTempDirectory("tablebridge-config-test-");var file=directory.resolve("choices.xml");
        try{
            var settings=new TransferConfiguration.Settings("PostgreSQL / 开发 / public","MySQL / 测试",true,true,false,ExistingPolicy.CLEAR,List.of(new TransferConfiguration.ObjectChoice("订单&明细","表","order_copy",true,true,Map.of(),"CREATE TABLE target.order_copy(id integer)\n"+TableStructureSql.SEPARATOR+"\nCREATE INDEX order_lookup ON target.order_copy(id)",Set.of()),new TransferConfiguration.ObjectChoice("v_orders","视图","v_orders",true,false,Map.of(),"CREATE VIEW target.v_orders AS SELECT * FROM target.order_copy",Set.of("订单&明细"))),true);
            TransferConfiguration.save(file,settings);check(TransferConfiguration.load(file).equals(settings),"configuration round trip preserves unicode and all choices");
            var invalid=java.nio.file.Files.readString(file).replace("<entry key=\"records\">true</entry>","<entry key=\"records\">maybe</entry>");java.nio.file.Files.writeString(file,invalid);
            boolean failed=false;try{TransferConfiguration.load(file);}catch(java.io.IOException expected){failed=true;}check(failed,"invalid config booleans rejected before apply");
        }finally{java.nio.file.Files.deleteIfExists(file);java.nio.file.Files.delete(directory);}
    }
    static void testOracleMappings(){
        check(CrossEngineDdl.supports(Engine.POSTGRES,Engine.ORACLE)&&CrossEngineDdl.supports(Engine.ORACLE,Engine.MYSQL),"Oracle participates in cross-engine matrix");
        check(CrossEngineDdl.type(column("id","bigint",Types.OTHER,19,0,false,null),Engine.POSTGRES,Engine.ORACLE).sql().equals("number(20,0)"),"bigint Oracle mapping keeps range");
        check(CrossEngineDdl.type(column("d","date",Types.DATE,0,0,false,null),Engine.ORACLE,Engine.POSTGRES).sql().equals("timestamp(0)"),"Oracle DATE includes time of day");
        check(CrossEngineDdl.type(column("n","number",Types.OTHER,0,0,false,null),Engine.ORACLE,Engine.POSTGRES).sql().equals("numeric"),"Oracle unbounded number maps to PostgreSQL numeric");
        check(CrossEngineDdl.type(column("n","number",Types.OTHER,0,0,false,null),Engine.ORACLE,Engine.MYSQL).sql().equals("longtext"),"Oracle unbounded range preserved as text rather than truncated");
        var ddl=CrossEngineDdl.build(Engine.POSTGRES,Engine.ORACLE,"TARGET","items",List.of(column("id","bigint",Types.OTHER,19,0,true,null)),List.of("id"));
        check(ddl.sql().contains("GENERATED BY DEFAULT AS IDENTITY")&&!ddl.sql().contains("AUTO_INCREMENT"),"Oracle identity syntax generated");
    }
    static void testCrossStructures()throws Exception{
        String index=CrossStructure.index(Engine.POSTGRES,"public","orders",new CrossStructure.Index("orders_customer",List.of("customer","created"),List.of(false,true),true));
        check(index.equals("CREATE UNIQUE INDEX \"orders_customer\" ON \"public\".\"orders\" (\"customer\", \"created\" DESC)"),"unique index scope and descending field preserved");
        String fk=CrossStructure.foreignKey(Engine.MYSQL,"test","orders",new CrossStructure.ForeignKey("fk_customer",List.of("customer"),"customers",List.of("id"),"CASCADE","NO_ACTION"));
        check(fk.contains("REFERENCES `test`.`customers` (`id`) ON DELETE CASCADE")&&CrossStructure.isForeignKey(fk),"foreign key points to target and executes after indexes");
        check(!CrossStructure.isForeignKey("COMMENT ON TABLE public.t IS 'FOREIGN KEY'")&&!CrossStructure.isForeignKey("CREATE INDEX \"FOREIGN\" ON public.t(\"KEY\")"),"post DDL classification ignores literals and quoted names");
        fails(()->CrossStructure.foreignKey(Engine.ORACLE,"T","orders",new CrossStructure.ForeignKey("fk",List.of("id"),"customers",List.of("id"),"NO_ACTION","CASCADE")),"Oracle rejects unsupported update cascade rather than changing behavior");
        check(CrossStructure.comment(Engine.POSTGRES,"public","orders",null,"customer's order").endsWith("'customer''s order'"),"table comment apostrophe quoted");
        check(CrossEngineDdl.type(column("v","varchar2(80 char)",Types.OTHER,80,0,false,null),Engine.ORACLE,Engine.POSTGRES).sql().equals("varchar(80)"),"Oracle character length semantics normalized");
        check(CrossEngineDdl.defaultSql(column("t","timestamp",Types.TIMESTAMP,0,0,false,"'2026-10-01 12:34:56.123456'::timestamp"),Engine.POSTGRES,Engine.ORACLE).equals(" DEFAULT TIMESTAMP '2026-10-01 12:34:56.123456'"),"Oracle temporal default uses NLS independent literal");
    }
    static void testFieldMappings()throws Exception{
        var source=List.of(column("labels","text[]",Types.ARRAY,0,0,false,"ARRAY['a']"));
        var mapping=new FieldMapping(Engine.MYSQL,"longtext",true,false);
        var ddl=CrossEngineDdl.build(Engine.POSTGRES,Engine.MYSQL,"target","items",source,List.of(),Map.of("labels",mapping));
        check(ddl.sql().contains("`labels` longtext")&&!ddl.sql().contains("ARRAY["),"manual complex type mapping and explicit omitted default applied");
        RowCopier.checkTypes(meta(Types.ARRAY),List.of(Types.VARCHAR));check(true,"explicit text mapping permits complex data serialization");
        fails(()->new FieldMapping(Engine.MYSQL,"varchar",true,false),"manual varchar length is checked before destructive DDL");
        fails(()->new FieldMapping(Engine.POSTGRES,"json(50)",true,false),"parameters rejected for non-parameterized types");
        fails(()->new FieldMapping(Engine.ORACLE,"number(39,0)",true,false),"Oracle manual numeric precision checked");
        fails(()->new FieldMapping(Engine.MYSQL,"varchar(50)); DROP TABLE x;--",true,false),"mapping cannot inject statements");
        fails(()->CrossEngineDdl.build(Engine.POSTGRES,Engine.ORACLE,"T","items",source,List.of(),Map.of("labels",mapping)),"mapping cannot follow a different target engine silently");
        var computed=new CrossEngineDdl.Column("total","numeric",Types.NUMERIC,20,2,false,false,"price * qty",true);
        var materialized=CrossEngineDdl.build(Engine.POSTGRES,Engine.MYSQL,"T","items",List.of(computed),List.of(),Map.of("total",new FieldMapping(Engine.MYSQL,"decimal(20,2)",true,true)));
        check(!materialized.sql().contains("GENERATED")&&materialized.warnings().stream().anyMatch(w->w.contains("物化")),"computed values become ordinary columns only on explicit choice");
        var directory=java.nio.file.Files.createTempDirectory("tb-mappings-");var file=directory.resolve("config.xml");try{
            var settings=new TransferConfiguration.Settings("source","target",true,false,false,ExistingPolicy.FAIL,List.of(new TransferConfiguration.ObjectChoice("items","表","items",true,true,Map.of("labels",mapping))));
            TransferConfiguration.save(file,settings);check(TransferConfiguration.load(file).equals(settings),"manual mappings round trip in config v2");
        }finally{java.nio.file.Files.deleteIfExists(file);java.nio.file.Files.delete(directory);}
    }
    static void testBulkCancellation()throws Exception{
        var remote=proxy(com.intellij.database.remote.jdbc.RemoteConnection.class,(p,m,a)->{throw new AssertionError("cancelled batch must not contact driver: "+m.getName());});
        try(PreparedStatement statement=RemoteBulk.prepare(remote,"INSERT INTO t(id) VALUES (?)",List.of(new com.intellij.database.remote.jdbc.ColumnInfo("","",Types.INTEGER,0)))){
            statement.setInt(1,42);statement.addBatch();statement.cancel();
            boolean stopped=false;try{statement.executeBatch();}catch(SQLException expected){stopped=true;}check(stopped,"cancelled multi-row batch never creates or executes driver statement");
        }
    }
    static void testBulkRows()throws Exception{
        int[] offset={0},calls={0};
        ResultSet source=(ResultSet)java.lang.reflect.Proxy.newProxyInstance(FeatureTest.class.getClassLoader(),new Class<?>[]{ResultSet.class,RemoteBulk.RowsApi.class},(p,m,a)->{
            if(m.getName().equals("fetchRows")){calls[0]++;List<Object[]> rows=new ArrayList<>();for(int end=Math.min(429,offset[0]+(Integer)a[0]);offset[0]<end;offset[0]++)rows.add(new Object[]{(long)offset[0],java.time.LocalDateTime.of(2026,9,30,12,0,0,123456000),offset[0]%2==0,null});return rows;}
            if(m.getName().equals("close"))return null;throw new AssertionError("unexpected per-cell source call: "+m.getName());
        });
        int count=0;boolean values=true;
        try(ResultSet rows=RemoteBulk.buffered(source,new int[]{Types.BIGINT,Types.TIMESTAMP,Types.BOOLEAN,Types.VARCHAR})){
            while(rows.next()){values&=rows.getBigDecimal(1).longValueExact()==count&&rows.getTimestamp(2).getNanos()==123456000&&rows.getBoolean(3)==(count%2==0)&&rows.getString(4)==null&&rows.wasNull();count++;}
        }
        check(count==429&&values,"buffered PG-style scalar values preserve order nulls booleans and nanos");
        check(calls[0]==5,"429 rows fetched in bounded pages plus EOF, no per-cell remote reads");
    }
    static void testControl()throws Exception{
        CountDownLatch cancelled=new CountDownLatch(1);Statement s=proxy(Statement.class,(p,m,a)->{if(m.getName().equals("cancel"))cancelled.countDown();return zero(m.getReturnType());});
        Connection c=proxy(Connection.class,(p,m,a)->m.getName().equals("createStatement")?s:zero(m.getReturnType()));
        TransferControl control=new TransferControl();try(Statement statement=control.wrap(c).createStatement()){control.cancel();check(cancelled.await(3,TimeUnit.SECONDS),"cancel reaches active JDBC asynchronously");}control.detach();
    }
}
