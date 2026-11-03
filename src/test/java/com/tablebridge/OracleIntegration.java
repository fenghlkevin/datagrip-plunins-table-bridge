package com.tablebridge;

import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;

/** Executes only in a newly created random schema on the explicitly supplied Oracle test PDB. */
public final class OracleIntegration {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){try{run(args);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
    private static void run(String[] args)throws Exception{
        Properties login=new Properties();if(args[0].equals("-"))login.load(System.in);else try(var input=Files.newInputStream(Path.of(args[0]))){login.load(input);}
        DriverManager.setLoginTimeout(15);
        String schema="TB_TEST_"+UUID.randomUUID().toString().replace("-", "").substring(0,12).toUpperCase(Locale.ROOT);
        String password="Tb_"+UUID.randomUUID().toString().replace("-", "").substring(0,20);boolean created=false;
        try(Connection admin=DriverManager.getConnection(login.getProperty("url"),login.getProperty("user"),login.getProperty("password"))){
            try(Statement q=admin.createStatement();ResultSet rs=q.executeQuery("SELECT sys_context('USERENV','CON_NAME') FROM dual")){check(rs.next()&&rs.getString(1).equalsIgnoreCase(login.getProperty("container", "ORCLPDB")),"explicit test PDB verified");}
            System.out.println("Oracle test database: "+admin.getMetaData().getDatabaseProductVersion().split("\n")[0]);
            try{
                RowCopier.execute(admin,"CREATE USER "+schema+" IDENTIFIED BY \""+password+"\" DEFAULT TABLESPACE USERS QUOTA 64M ON USERS");created=true;
                RowCopier.execute(admin,"GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE PROCEDURE, CREATE SEQUENCE, CREATE TYPE, CREATE TRIGGER TO "+schema);
                try(Connection target=DriverManager.getConnection(login.getProperty("url"),schema,password)){
                    var columns=List.of(new CrossEngineDdl.Column("id","bigint",Types.OTHER,19,0,true,true,null,false),new CrossEngineDdl.Column("amount","numeric(20,4)",Types.NUMERIC,20,4,false,false,"0",false),new CrossEngineDdl.Column("note","varchar(50)",Types.VARCHAR,50,0,false,false,null,false),new CrossEngineDdl.Column("create_time","timestamp without time zone",Types.TIMESTAMP,0,0,true,false,"now()",false),new CrossEngineDdl.Column("enabled","boolean",Types.BOOLEAN,0,0,false,false,"true",false),new CrossEngineDdl.Column("payload","bytea",Types.VARBINARY,0,0,false,false,null,false));
                    var definition=CrossEngineDdl.build(Engine.POSTGRES,Engine.ORACLE,schema,"items",columns,List.of("id"));
                    RowCopier.execute(target,definition.sql());check(true,"PostgreSQL-to-Oracle generated DDL executes");
                    target.setAutoCommit(false);
                    try(PreparedStatement insert=target.prepareStatement("INSERT INTO "+Engine.ORACLE.qualified(schema,"items")+" (\"id\",\"amount\",\"note\",\"payload\") VALUES(?,?,?,?)")){
                        for(int i=0;i<429;i++){insert.setLong(1,i);insert.setBigDecimal(2,new BigDecimal("1234567890123456.1234"));if(i%2==0)insert.setNull(3,Types.VARCHAR);else insert.setString(3,"中文🙂'"+i);insert.setBytes(4,new byte[]{0,(byte)255});insert.addBatch();}insert.executeBatch();
                    }
                    try(Statement q=target.createStatement();ResultSet rs=q.executeQuery("SELECT count(*),max(\"amount\") FROM "+Engine.ORACLE.qualified(schema,"items"))){check(rs.next()&&rs.getLong(1)==429,"429 records inserted");check(rs.getBigDecimal(2).equals(new BigDecimal("1234567890123456.1234")),"Oracle decimal fidelity");}
                    target.rollback();check(TargetValidation.count(target,Engine.ORACLE.qualified(schema,"items"),Engine.ORACLE)==0,"Oracle data rollback verified");
                    RowCopier.execute(target,"INSERT INTO "+Engine.ORACLE.qualified(schema,"items")+" (\"note\") VALUES ('default row')");
                    try(Statement q=target.createStatement();ResultSet rs=q.executeQuery("SELECT \"id\",\"create_time\",\"enabled\" FROM "+Engine.ORACLE.qualified(schema,"items"))){check(rs.next()&&rs.getLong(1)>0&&rs.getTimestamp(2)!=null&&rs.getInt(3)==1,"identity timestamp and boolean defaults execute");}
                    target.rollback();
                }
            }finally{if(created){RowCopier.execute(admin,"DROP USER "+schema+" CASCADE");System.out.println("Cleaned task-owned Oracle schema: "+schema);}}
        }finally{login.clear();}
        String result="PASS "+checks+" real Oracle checks; random task-owned schema cleaned.\n";Files.writeString(Path.of(args[1]),result);System.out.print(result);
    }
}
