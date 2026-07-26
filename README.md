[![License](https://img.shields.io/github/license/SkyDynamic/QuickBackupM-Fabric.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Issues](https://img.shields.io/github/issues/QuickBackupMultiMod-Dev/QuickBackupM-Reforged.svg)](https://github.com/QuickBackupMultiMod-Dev/QuickBackupM-Fabric/issues)
[![Modrinth](https://img.shields.io/modrinth/dt/DgWBIBY5?label=Modrinth%20Downloads)](https://modrinth.com/mod/quickbackupmulti)
[![CurseForge](https://img.shields.io/curseforge/dt/951047?label=CurseForge%20Downloads)](https://www.curseforge.com/minecraft/mc-mods/quickbackupmulti)
[![v2 Github Release](https://img.shields.io/github/downloads/QuickBackupMultiMod-Dev/QuickBackupM-Fabric/total?label=V2%20Github%20Downloads)](https://github.com/QuickBackupMultiMod-Dev/QuickBackupM-Fabric/releases)
[![v3 Github Release](https://img.shields.io/github/downloads/QuickBackupMultiMod-Dev/QuickBackupM-Reforged/total?label=V3%20Github%20Downloads)](https://github.com/QuickBackupMultiMod-Dev/QuickBackupM-Reforged/releases)

<div align="center">
<a><img src="./indexImg.png" width="180" height="180" alt="Logo"></a>
</div>
<div align="center">

# QuickBackupMulti-Reforged

**简体中文** | [English]()

_✨ MC备份 / 回档模组 ✨_  
重构自MCDR插件: [QuickBackupMulti](https://github.com/TISUnion/QuickBackupM)
与 Mod [QuickBackupMulti-Fabric](https://github.com/QuickBackupMultiMod-Dev/QuickBackupM-Fabric)

## 警告
### 如果你使用的是v3.1.0之后的mod, 你的之前的备份将会永久丢失, 因为3.1.0更换了更优秀的备份算法, 请做好数据备份

</div>

> [!WARNING]  
> v3与v2的数据库并不一致, 无法直接迁移
> 
> 数据库迁移可以使用 [这个工具](https://github.com/QuickBackupMultiMod-Dev/Qbm-DatabaseMerge/releases)

> 当前Mod大版本为`v3`, 相比于`v2`, 使用了更高性能的数据库, 并且重构了项目结构与简化了代码
> 
> 本mod已支持\NeoForge/

## 关于本 Fork (MC 26.2)

> [!NOTE]
> 本分支 (`26.2`) 将 mod 从 MC 1.21 迁移到 **MC 26.2**。全部代码迁移、修复以及本 README 说明均由 Anthropic 的 AI 模型 **Claude (Fable 5)** 在 Claude Code 中完成。此 fork 按现状提供 (as-is), **不承诺后续维护**。

### 主要更改

**工具链迁移** (MC 26.1+ 不再混淆, Yarn/intermediary 停更):
- 迁移到 `dev.architectury.loom-no-remap`, 移除 mappings 与 remapJar, shadowJar 为最终产物
- Gradle 9.5 / Java 25 / Fabric Loader 0.19.3 / Fabric API 0.155.2+26.2 / NeoForge 26.2.0.32-beta
- 适配 26.2 API: GuiGraphicsExtractor 渲染管线、sealed Click/HoverEvent、新权限系统、11 参 MinecraftServer 构造器、新 Mixin 注入点签名等

**数据安全加固** (两轮多代理审查 + 对抗验证, 修复约 20 项问题):
- 新增全局操作互斥锁, 备份与回档不再可能并发撕裂 blob 存储
- 客户端回档失败自动回滚到临时备份; 回滚也失败时落盘 rescue 标记, 拒绝覆盖唯一幸存副本
- 备份失败时正确恢复世界的 noSave 标志 (原实现会导致世界永久停止自动保存)
- 全量备份移入 noSave 窗口内执行, 避免撕裂快照
- Quartz 调度器改为共享单例, 停止单个任务不再杀死其他定时任务
- 修复 NeoForge 26.x 移除 `@OnlyIn` 运行时剥离导致的服务器停止事件双重处理
- 删除世界时的路径校验, 防止误删备份存储根目录

**功能修复与新增**:
- 恢复 `/qb back` 别名 (兼容 MCDR 时代肌肉记忆)
- 支持中文等非 ASCII 备份名 (补全自动加引号)
- `/qb search` 同时搜索备份描述
- 修复 26.2 下回档界面文字不可见 (零 alpha 颜色被新文本管线跳过)

**性能优化**:
- H2 数据库常驻连接 (原实现每次查询完整开关数据库)
- Tab 补全加短 TTL 缓存 (原实现每次按键全表扫描且在服务器主线程)
- 回档文件重建并行化, 路径解析移出循环
- `/qb search` 从 O(m×n) 降为单次遍历
- 删除世界时的备份清理移出渲染线程
- 全量备份触发时向玩家发送提示 (原先分钟级停顿无任何反馈, 易被误认为卡死)

## 本Mod优势
- 支持回档自动重启服务器, 不再是只备份不回档
- 客户端支持回档自动重进存档! 

## 使用方式
> [!WARNING]  
> 严禁自行删除备份文件夹内的所有备份文件, 如需删除请进入游戏内进行手动删除! 

> 在使用mod前请确保你已安装Fabric Loader

将本mod放进`mods`文件夹即可

## 配置说明
详见 [配置说明](./docs/zh_cn/config.md)

## 指令
`/qb` 或 `/quickbackupmulti`均可触发mod

## 特性
- [x] 定时备份
- [x] 无限槽位
- [x] Hash对比并仅备份差异文件
- [x] 个性化设置

## 许可
本项目遵循 [LGPL-3.0 License](https://www.gnu.org/licenses/lgpl-3.0.en.html) 许可
