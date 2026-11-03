package com.tablebridge;

enum ExistingPolicy {
    FAIL("停止并提示"), APPEND("追加数据（保留目标结构）"), CLEAR("清空数据后复制（保留目标结构）"), RECREATE("删除并重建表（结构和数据）"), UPSERT("增量：按主键新增/更新"), MIRROR("同步：按主键新增/更新/删除");
    final String label;ExistingPolicy(String label){this.label=label;}
    boolean destructive(){return this==CLEAR||this==RECREATE||this==MIRROR;}
    @Override public String toString(){return UiText.text(label);}
}
