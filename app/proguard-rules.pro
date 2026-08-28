# StudyKit release 混淆规则
#
# 说明：
# - Room / Compose / Coil / WorkManager 均随 AAR 自带 consumer rules，
#   KSP 生成的 Room 实现类为直接调用（非反射），理论上无需额外 keep；
# - 若后续引入反射型库（如 Gson / Moshi / java.io.Serializable 深度依赖），
#   需在此补充对应 keep 规则；
# - 混淆后的 release 包必须在本地真机完整回归验证后再发布（详见 CHANGELOG.md）。

# 保留崩溃堆栈中的源码行号，便于线上问题定位（不阻碍压缩）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留 Room 注解信息（双保险：实体字段名与 @ColumnInfo 映射在 release 下保持可读）
-keep @androidx.room.Entity class *
-keep class * extends androidx.room.RoomDatabase

# Compose 编译器生成的工具类不参与重命名（Compose 自带规则已覆盖，此处防御性保留）
-dontwarn org.jetbrains.annotations.**
