#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_migration.py —— 离线校验 Room 迁移是否与实体生成的目标 schema 一致

为什么需要它：Room 在打开数据库时会逐列比对「期望 schema」与「实际 schema」，
任何一列的名称/类型/NOT NULL/默认值不一致都会在用户设备上直接崩溃。
本项目 exportSchema=false，没有 schema JSON 可比，因此这里用 Room 自己生成的建表 SQL 当基准：

  1. 取 v2 代码（git worktree）生成的 createTablesSql，建出旧库；
  2. 从 AppDatabase.kt 里解析出 MIGRATION_2_3 的全部 execSQL 语句并执行；
  3. 与 v3 代码生成的 createTablesSql 逐表比对列、主键、索引、外键。

用法（在项目根目录）：
  python tools/check_migration.py <v2生成目录> <v3生成目录>
  两个目录形如 app/build/generated/ksp/debug/kotlin

退出码 0 表示迁移安全，1 表示存在差异（会打印差异明细）。
"""
import glob
import os
import re
import sqlite3
import sys

STATEMENT_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
CREATE_RE = re.compile(r"^\s*(CREATE|DROP)\b", re.IGNORECASE)
KEEP_RE = re.compile(r"^\s*(CREATE|ALTER|DROP)\b", re.IGNORECASE)


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def collected_sql_from_generated(root):
    """从 Room 生成的 *AppDatabase_Impl 源文件里取出所有建表/建索引语句

    只看 CREATE 开头的语句：同一个文件里还有 deleteAllTables 的 DROP TABLE，
    一并执行会把刚建好的表删掉。
    """
    files = glob.glob(os.path.join(root, "**", "*AppDatabase_Impl*"), recursive=True)
    if not files:
        raise SystemExit("找不到 Room 生成的 AppDatabase_Impl：%s" % root)
    text = "\n".join(read(f) for f in files)
    statements = []
    for literal in STATEMENT_RE.findall(text):
        unescaped = literal.replace("\\`", "`").replace('\\"', '"').replace("\\n", "\n")
        for piece in unescaped.split(";"):
            piece = piece.strip()
            if piece and piece.upper().startswith("CREATE "):
                statements.append(piece)
    return statements


def migration_statements(kotlin_file, migration_name):
    """解析 MIGRATION_x_y 里每条 db.execSQL(...) 的语句（支持多段字符串拼接）"""
    src = read(kotlin_file)
    start = src.index("val " + migration_name)
    body = src[start:]
    # 截到下一个 val / @Volatile，避免把别的迁移也算进来
    stop = re.search(r"\n        (?:val |@Volatile)", body)
    if stop:
        body = body[: stop.start()]
    statements = []
    for call in re.finditer(r"execSQL\((.*?)\)\s*\n", body, re.DOTALL):
        args = call.group(1)
        parts = STATEMENT_RE.findall(args)
        if not parts:
            continue
        sql = "".join(parts).replace("\\`", "`").replace('\\"', '"').strip()
        if KEEP_RE.match(sql):
            statements.append(sql)
    return statements


def table_infos(conn):
    infos = {}
    names = [r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
    )]
    for name in names:
        columns = {}
        for cid, cname, ctype, notnull, dflt, pk in conn.execute('PRAGMA table_info("%s")' % name):
            columns[cname] = dict(
                type=(ctype or "").upper(), notnull=int(notnull or 0),
                dflt=(str(dflt) if dflt is not None else None), pk=int(pk or 0),
            )
        # PRAGMA index_list: seq, name, unique, origin, partial —— seq 受创建顺序影响，不能参与比较
        indexes = dict(
            (r[1], (int(r[2] or 0), int(r[4] or 0)))
            for r in conn.execute('PRAGMA index_list("%s")' % name)
            if not r[1].startswith("sqlite_autoindex")
        )
        fks = sorted(
            (r[2], r[3], r[4], r[5], r[6], r[7]) for r in conn.execute('PRAGMA foreign_key_list("%s")' % name)
        )
        infos[name] = dict(columns=columns, indexes=indexes, fks=fks)
    return infos


def norm_default(value):
    """Room 与 sqlite 的默认值写法差异要归一化：'' 与 '2' 这类字面量去掉外层引号，浮点归一"""
    if value is None:
        return None
    v = value.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
        return v[1:-1]
    try:
        f = float(v)
        return ("%g" % f)
    except ValueError:
        return v


def diff_schemas(before, after):
    issues = []
    missing = sorted(set(after) - set(before))
    extra = sorted(set(before) - set(after))
    for t in missing:
        issues.append("缺少新表：%s" % t)
    for t in extra:
        issues.append("多出的表（v3 里没有）：%s" % t)
    for t in sorted(set(after) & set(before)):
        a, b = before[t]["columns"], after[t]["columns"]
        for col in sorted(set(b) - set(a)):
            issues.append("%s 缺少列：%s" % (t, col))
        for col in sorted(set(a) - set(b)):
            issues.append("%s 多出列：%s" % (t, col))
        for col in sorted(set(a) & set(b)):
            x, y = a[col], b[col]
            if x["type"] != y["type"]:
                issues.append("%s.%s 类型不同：迁移后=%s 期望=%s" % (t, col, x["type"], y["type"]))
            if x["notnull"] != y["notnull"]:
                issues.append("%s.%s NOT NULL 不同：迁移后=%s 期望=%s" % (t, col, x["notnull"], y["notnull"]))
            if norm_default(x["dflt"]) != norm_default(y["dflt"]):
                issues.append("%s.%s 默认值不同：迁移后=%s 期望=%s" % (t, col, x["dflt"], y["dflt"]))
            if x["pk"] != y["pk"]:
                issues.append("%s.%s 主键标记不同：迁移后=%s 期望=%s" % (t, col, x["pk"], y["pk"]))
        ia, ib = before[t]["indexes"], after[t]["indexes"]
        for idx in sorted(set(ib) - set(ia)):
            issues.append("%s 缺少索引：%s" % (t, idx))
        for idx in sorted(set(ia) - set(ib)):
            issues.append("%s 多出索引：%s" % (t, idx))
        for idx in sorted(set(ia) & set(ib)):
            if ia[idx] != ib[idx]:
                issues.append("%s 索引 %s 属性不同：迁移后=%s 期望=%s" % (t, idx, ia[idx], ib[idx]))
        fa, fb = before[t]["fks"], after[t]["fks"]
        if sorted(fa) != sorted(fb):
            issues.append("%s 外键定义不同：\n  迁移后=%s\n  期望=%s" % (t, fa, fb))
    return issues


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    v2_root, v3_root = sys.argv[1], sys.argv[2]
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    kotlin_db = os.path.join(project_root, "app", "src", "main", "java", "com", "studykit", "data", "AppDatabase.kt")

    v2_sql = collected_sql_from_generated(v2_root)
    v3_sql = collected_sql_from_generated(v3_root)
    migs = migration_statements(kotlin_db, "MIGRATION_2_3")
    print("v2 建表语句 %d 条 / v3 建表语句 %d 条 / MIGRATION_2_3 语句 %d 条" % (len(v2_sql), len(v3_sql), len(migs)))
    if not migs:
        print("解析不到 MIGRATION_2_3，检查 AppDatabase.kt 是否被改动过格式")
        return 1

    conn = sqlite3.connect(":memory:")
    conn.executescript("PRAGMA foreign_keys=ON;\n" + ";\n".join(v2_sql) + ";")
    for stmt in migs:
        try:
            conn.execute(stmt)
        except sqlite3.Error as exc:
            print("迁移语句执行失败：%s\n  %s" % (exc, stmt[:160]))
            return 1
    conn.commit()

    migrated = table_infos(conn)
    expected = table_infos_fresh(v3_sql)
    print("迁移后表：%s" % ", ".join(sorted(migrated)))
    print("v3 直接建表：%s" % ", ".join(sorted(expected)))
    issues = diff_schemas(migrated, expected)
    if issues:
        print("迁移结果与 v3 期望 schema 不一致（%d 处）：" % len(issues))
        for line in issues:
            print("  - " + line)
        return 1
    print("OK：v2 加迁移后的 schema 与 v3 完全一致（表/列/主键/默认值/索引/外键）")
    return 0


def table_infos_fresh(create_sql):
    """把 v3 的建表语句单独建一个库，取其 schema 作为期望值"""
    conn = sqlite3.connect(":memory:")
    conn.executescript(";\n".join(create_sql) + ";")
    return table_infos(conn)


if __name__ == "__main__":
    sys.exit(main())
