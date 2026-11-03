package com.tablebridge;
import com.intellij.database.psi.DbElement;
import com.intellij.database.model.*;
import java.util.*;
/** Stable routine signature for UI, configuration and recovery; no connection secrets. */
final class ObjectIdentity {
    static String signature(DbElement element){
        if(!(element.getDelegate() instanceof DasRoutine routine))return "";
        List<String> args=new ArrayList<>();for(var arg:routine.getArguments())if(arg.getArgumentDirection()!=ArgumentDirection.RETURN)args.add(arg.getArgumentDirection()+":"+arg.getDataType().getSpecification()+":"+arg.isVariadic());
        return routine.getRoutineKind()+"("+String.join(",",args)+")";
    }
    static String key(DbElement element){return element.getName()+"\u0000"+element.getKind()+"\u0000"+signature(element);}
}
