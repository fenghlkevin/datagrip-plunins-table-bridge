package com.tablebridge;

import java.math.BigInteger;

/** Native standalone sequence translation. MySQL has no equivalent schema object. */
final class PortableSequence {
    record Spec(BigInteger next,BigInteger increment,BigInteger min,BigInteger max,long cache,boolean cycle){}
    static String create(Engine target,String namespace,String name,Spec spec){
        if(target!=Engine.POSTGRES&&target!=Engine.ORACLE)throw new IllegalArgumentException("MySQL 不支持独立序列；不能把序列静默改成 AUTO_INCREMENT，需选择仿真迁移策略");
        if(spec.next==null||spec.increment==null||spec.min==null||spec.max==null)throw new IllegalArgumentException("序列当前值/边界元数据未完整加载："+name);
        BigInteger limit=target==Engine.POSTGRES?BigInteger.valueOf(Long.MAX_VALUE):BigInteger.TEN.pow(28).subtract(BigInteger.ONE);
        BigInteger floor=target==Engine.POSTGRES?BigInteger.valueOf(Long.MIN_VALUE):BigInteger.TEN.pow(27).subtract(BigInteger.ONE).negate();
        if(spec.min.compareTo(floor)<0||spec.max.compareTo(limit)>0||spec.min.compareTo(spec.max)>=0||spec.next.compareTo(spec.min)<0||spec.next.compareTo(spec.max)>0||spec.increment.signum()==0||spec.increment.abs().compareTo(spec.max.subtract(spec.min))>=0)throw new IllegalArgumentException("序列值域不能保真迁移："+name);
        if(spec.cache<0)throw new IllegalArgumentException("序列缓存参数不能映射："+name);
        if(spec.cache>1&&BigInteger.valueOf(spec.cache).compareTo(spec.max.subtract(spec.min).divide(spec.increment.abs()).add(BigInteger.ONE))>=0)throw new IllegalArgumentException("序列缓存大于值域容量："+name);
        return "CREATE SEQUENCE "+target.qualified(namespace,name)+" INCREMENT BY "+spec.increment+" MINVALUE "+spec.min+" MAXVALUE "+spec.max+" START WITH "+spec.next+(target==Engine.POSTGRES?" CACHE "+Math.max(1,spec.cache):spec.cache<=1?" NOCACHE":" CACHE "+spec.cache)+(spec.cycle?" CYCLE":target==Engine.POSTGRES?" NO CYCLE":" NOCYCLE");
    }
}
