# AGENTS.md

Minecraft Paper/Spigot 抢答插件（答题发金币）。用户可见文案与注释一律中文，新代码保持一致。

## 构建与验证

- **唯一可用的构建命令：`mvn clean package`** → `target/LetMeAsk-1.1.0.jar`。需要联网（paper-api 为 papermc 仓库的 SNAPSHOT）。
- **不要用 `gradle build`**：系统 Gradle 是 4.4.1，而 `build.gradle` 用了 toolchain（需 6.7+），脚本求值阶段即失败；仓库也没有 `gradlew` wrapper。`build.gradle`、`settings.gradle`、`libs/`、`sources.txt` 是另一套构建的遗留物，`libs/*.jar` 是本地 paper-api 副本（pom 用不到，别改 pom 去配合它）。
- 没有测试、没有 lint/format 配置。**编译通过就是全部本地验证手段。**
- 版本号单一来源是 `pom.xml` 的 `<revision>`：`plugin.yml` 写 `${revision}` 由 Maven 资源过滤代入，`build.gradle` 用 `XmlParser` 读 pom，所以只改 pom（README 里手写的 jar 文件名例外）。注意 CI 会另加 `-<sha>` 短哈希后缀。
- **推 `main` 会触发 `.github/workflows/build-release.yml`**：push 即 CI 构建并覆盖发布到 `latest` 这个滚动 release（预发布）。推之前先本地 `mvn clean package` 过一遍。
- `.github/modernize/` 只是两个 hook 脚本，不是 CI。
- `pom.xml` 目标 release 17；本机 JDK 25，可直接编译。

## 代码结构

- 实际只有一个源文件：`src/main/java/com/cubex/quiz/QuizPlugin.java`（约 1400 行，含内部类 `Question`、`QuizCommand`、`ChatHistory`）。命令、配置、经济、防作弊全在这里，不存在其他包可去。
- `plugin.yml` 与代码必须同步：子命令集中在 `QuizCommand.onCommand` 的 switch，帮助文案在 `usage:`，补全列表在 `onTabComplete`。新增子命令要同时改这三处 + README 命令表。

## 可选依赖走反射，别加进 pom

- Vault 经济、HumanVerify **不是编译期依赖**，全部通过 `Class.forName(...)` + `Bukkit.getServicesManager()` 反射调用（如 `org.cubexmc.humanverify.api.HumanVerifyApi`）。**不要为它们添加 Maven 依赖**；这些字符串必须与外部插件的包名严格一致，且缺失时要优雅降级（插件照常加载，只是该功能不可用）。

## 配置文件的坑

- `base.yml` / `questions.yml` 用 `saveResource(name, false)` 释放，**只在文件不存在时写入**。已安装的服务器永远拿不到新增的默认键，所以：
  - 每个新配置键必须在 `loadConfigValues()` 里用带默认值的 `getX(key, default)` 读取；
  - 新键要补进 `src/main/resources/base.yml` 的注释块和 README 的配置示例。
- 启动时题库为空 → 禁用插件；`/lma reload` 时题库为空 → 保留旧题库继续运行（见 `loadConfigValues` 返回值语义）。
- 面向玩家的可配置消息走 `msg(key, def, arg)`，键定义在 `base.yml` 的 `messages:` 下，`{arg}` 为占位符。

## 线程与时序（改动答题/验证流程前必读）

- 出题状态机由主线程 `tick()` 驱动：`runTaskTimer(this, 20L, 20L)`（1 秒粒度，负责题目超时与人机验证超时兜底）。
- HumanVerify 回调来自外部线程，必须经 `Bukkit.getScheduler().runTask(...)` 切回主线程后再碰游戏状态。
- 回调落地前要用 `verifyEpoch` 判定过期（`clearQuestionState()` 会自增），并校验 `verifyingPlayer` / `currentQuestion.id`。reload、force、超时兜底都会使旧回调作废——**丢掉这层判断会导致过期回调错误发奖或踢人**。
- 状态字段多为 `volatile`，但复合操作没有锁；`recentChatMessages` 用 `synchronized`，`stats.yml` 有异步 30s 增量保存（`saveStats` 的 dirty 语义）。

## 相关约定

- 兼容层：Bukkit legacy 文本 API 统一封装为 `broadcastLegacy` / `kickLegacy`（带 `@SuppressWarnings("deprecation")`），新增广播/踢人请复用它们。
- 文案里的颜色码统一用 `§`（配置里写 `&`，加载时替换）；消息前缀有 `cachedPrefix` 缓存，reload 时失效。
