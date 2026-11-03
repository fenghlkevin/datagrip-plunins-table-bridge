package com.tablebridge;
import java.sql.*;
import java.util.*;
import java.nio.file.*;
/** Separate JVM verifies recovery without any in-memory parent state. */
public final class RecoveryWorker {
    public static void main(String[] args)throws Exception{
        Engine e=Engine.valueOf(args[0]);String url=e==Engine.MYSQL?"jdbc:mysql://127.0.0.1:33316/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC":"jdbc:postgresql://127.0.0.1:33317/postgres";
        try(Connection c=DriverManager.getConnection(url,e==Engine.MYSQL?"root":System.getProperty("user.name"),"")){
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery(e==Engine.MYSQL?"SELECT @@datadir":"SHOW data_directory")){r.next();Path expected=Path.of(e==Engine.MYSQL?"build/mysql-integration/data-local":"build/pg-integration/data").toRealPath();if(!Path.of(r.getString(1)).toRealPath().equals(expected))throw new SQLException("not task-owned database");}
            RecoveryIntegration.setup(c,e);var job=new ResumableTable(c,e,"tb_matrix",args[1],true,"network",()->{},line->{});
            var spec=new ResumableTable.Spec(e.qualified("tb_matrix","network_source"),e.qualified("tb_matrix","network_target"),List.of("id","note"),List.of("id"),List.of(Types.INTEGER,Types.VARCHAR),false,ExistingPolicy.APPEND);
            if(!job.get(0).phase().equals("APPLIED")||job.apply(0,spec,false)!=0||TargetValidation.count(c,spec.target(),e)!=602)throw new AssertionError("fresh process checkpoint mismatch");c.rollback();System.out.println("PASS fresh JVM recovery "+e);
        }
    }
}
