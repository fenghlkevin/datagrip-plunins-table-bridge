package com.tablebridge;

/** Exact supported engines; intentionally excludes MariaDB and cloud variants. */
public enum Engine {
    MYSQL, POSTGRES, ORACLE, MSSQL;
    public String quote(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) throw new IllegalArgumentException("对象名称不能为空");
        return switch (this) {
            case MYSQL -> "`" + name.replace("`", "``") + "`";
            case MSSQL -> "[" + name.replace("]", "]]") + "]";
            default -> "\"" + name.replace("\"", "\"\"") + "\"";
        };
    }
    public String qualified(String namespace, String name) { return quote(namespace) + "." + quote(name); }
}
