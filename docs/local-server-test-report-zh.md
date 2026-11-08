# 本地服务器实测报告（新子系统）

测试环境：`E:\MC\tra\Ets`（Leaf 1.21.4-525，Java 25，离线模式，端口 1082），
插件 `Sertraline-3.13.0.5-Premium-Standard.jar`，假人 `E:\mineflayer`（mineflayer 4.39.0）。
测试后已回滚：`server.properties` 还原、临时配方/探针插件删除、测试 OP 移除。

## 一、插件加载

| 检查项 | 结果 |
|---|---|
| 加载物品 | 34 items，无异常 |
| 合成站 / 脚本 / 全局监听 | 1 / 3 / 1，正常 |
| 启动与 `/sertraline reload` | 均通过，无堆栈 |
| 唯一异常 | FastAsyncWorldEdit 更新检查的证书错误（与本项目无关） |

## 二、物品处理器开关（验证 `config.yml` 补齐的键）

```
sertralinedebug processors    -> [xbuilder, sertraline, custom_data, chotenatb, chotenatb_complex, mmo, minecraft, common]
sertralinedebug tagProcessors -> [papi, sertraline, nbt, gem-slot]
```

新补进默认配置的 `custom_data`、`chotenatb`、`chotenatb_complex`、`nbt`、`gem-slot` 都是真实注册名，
不是杜撰的键。

## 三、配方引用解析（本次核心改动）

用一份覆盖全部分支的临时配方集实测，`/sertraline reload` 后日志：

| 输入 | 实际行为 |
|---|---|
| `stone` / `diamond`（裸名） | 静默解析为原版，无告警 |
| `doe_card`（裸名，是 Sertraline 物品） | 告警 + 回落 Sertraline，配方注册成功 |
| `totally_missing_item_xyz`（裸名，不存在） | 告警 + `Unknown Sertraline item`，配方被跳过，**不崩** |
| `minecraft:totally_fake_material` | `Unknown item reference`，**不**回落 |
| `sertraline:doe_card` | 无告警 |
| `itemsadder:some_custom_item` | `Failed to build external item`，优雅失败 |

告警原文：

```
[Recipe] 'doe_card' is not a vanilla material, falling back to Sertraline item 'doe_card'.
        Write it as 'sertraline:doe_card' to silence this warning.
```

## 四、`AbstractSItem` 多态与序列化（新增 `evalJS` / `evalGraalJs` 实测）

| 断言 | 实测返回 |
|---|---|
| `parse("minecraft::stone")` | `MinecraftSItem \| minecraft::stone \| shouldAdapt=false \| STONE` |
| `parse("doe_card")` | `ModernSItem \| doe_card` |
| `parse("someplugin::gun")` | `ExternalPluginSItem \| someplugin::gun \| shouldAdapt=true` |
| `register(MinecraftSItem)` | 注册表 34 → 35 |
| `isRegistered("minecraft::diamond")` | `true`，`getMinecraftItems()` 数量 1 |
| `getItem("nonexistent::x")` | `null`（**不**回落到裸 key） |
| `doe_card` 序列化往返 | `ModernSItem \| doe_card \| shouldAdapt=true \| 65 bytes` |
| `minecraft::stone` 序列化往返 | `MinecraftSItem \| minecraft::stone \| shouldAdapt=false` |
| `deserialize("{bad}")` / `parse("::stone")` | `null` / `null`（不抛异常） |
| `evalJS`（Nashorn 路径） | `MinecraftSItem \| minecraft::stone \| STONE` |

关键点：Gson 用 `Unsafe` 还原，**不执行构造器、也不执行默认值表达式**，所以
`shouldAdapt` 必须由 `SItemFactory` 重新赋值。上表中两个不同的往返结果
（Sertraline=true / Minecraft=false）证明这条链路成立，而不是碰巧取到同一个默认值。

## 五、测试中发现并修复的两个真实缺陷

### 1. `evalJS` / `evalGraalJs` 在异步线程上找不到插件类

现象：`Java.type('io.github.zzzyyylllty.sertraline...')` 报
`Access to host class ... is not allowed or does not exist` / `ClassNotFoundException`。

原因有两层：

- 子命令跑在 `submitAsync` 的异步线程上，该线程的上下文类加载器是服务端/paperclip 的，
  不是插件类加载器；`GraalJsUtil` 的白名单本身是允许 `io.github.zzzyyylllty.sertraline.*` 的，
  缺的是 TCCL。
- `evalJS` 最初用 TabooLib 的 `compileJS`，它的引擎由 TabooLib 自己创建，类加载器看不到插件类，
  即使切了 TCCL 也没用。

修法：两个子命令都在切换 TCCL 的包装内执行；`evalJS` 改走 `ScriptHelper`（其
`nashornEngineHolder` 是用 `GraalJsUtil` 的类加载器创建引擎的）。

### 2. `Material` 初始化失败会静默把所有原版配料变成 Sertraline 查找

`resolveRecipeReference` 最初用 `runCatching { Material.matchMaterial(name) }`，
而 `runCatching` 连 `NoClassDefFoundError` 一起吞。已改为只捕获 `Exception`：

```kotlin
private fun matchMaterial(name: String): Material? =
    try { Material.matchMaterial(name) } catch (e: Exception) { null }
```

## 六、测试脚本（保留在仓库内可复用）

| 文件 | 用途 |
|---|---|
| `docs/test-e2e.ps1` | 一条命令完成 起服 → 跑断言 → 停服（起停在同一任务内，避免进程被回收） |
| `docs/node/rcon.js` | 纯 Node 的 RCON 客户端，无依赖 |
| `docs/node/bot-cmd.js` | 假人执行游戏内命令并抓取聊天回复（RCON 收不到异步回复） |
| `docs/node/bot-test.js` | 假人取物品并 dump 物品 NBT |
| `docs/probe-plugin/` | 曾尝试用外部插件调 API，后改用 `evalJS`，未启用 |

注意：Bash/PowerShell 侧启动服务器必须保持 stdin 打开，否则服务器在 `Done` 之后会因 EOF 自动关服。

## 七、构建环境问题（非代码问题）

测试期间构建反复报 `Gradle build daemon disappeared unexpectedly`，一度被误判为内存不足。
实际原因是 **E 盘只剩约 2 GB**（E 盘被 QQ 73 GB / MC 59 GB 等用户数据占满），
Gradle 守护进程写临时文件失败后直接消失，日志里不会留异常堆栈。

规避方式（已验证）：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11+10'
$env:GRADLE_OPTS='-Xmx1g -Djava.io.tmpdir=C:\GradleTmp'
./gradlew :plugin:build --console=plain --no-daemon
```

另外 `./gradlew build` 还会跑 javadoc 等重任务，在磁盘紧张时更容易失败；只验证代码改动时
用 `:project:common:compileKotlin` 或 `:plugin:build` 即可。
