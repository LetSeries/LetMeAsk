# LetMeAsk

一个 Minecraft 服务器抢答活动插件，定时在聊天栏出题，玩家抢答可获得金币奖励。

## 功能特性

- **定时自动出题**：按照配置间隔自动发布题目
- **答题超时**：超时无人答对时自动公布答案并出下一题
- **经济系统集成**：支持 Vault 经济插件，自动扣款/发放奖励
- **模糊匹配**：答案支持容错匹配（拼写相似度可配置）
- **人机验证**：答题过快或连续答对过多时触发 HumanVerify 验证
- **防脚本枚举**：本轮题目期间聊天刷屏过快直接踢出（`anti-bot-chat-*` 配置）
- **答对特效**：答对者收到 Title 标题，全服播放升级音效（均可在 `celebrate` 下配置开关）
- **物品奖励**：与金币叠加发放，支持自定义名/lore/附魔，可随机其一，背包满掉地上（`items` 配置）
- **多语言**：`language: zh/en` 一键切换，英文段缺键自动回退中文
- **灵活配置**：支持玩家名、UUID、服务器账户、LittleSkin 等支付方式

注意: 人机验证需要依赖[HumanVerify](https://github.com/FZAoao/HumanVerify)插件。如果没有它，人机验证功能将无法使用，但是基本功能不会影响。

## 环境要求

| 依赖 | 类型 | 版本 |
|------|------|------|
| Paper/Spigot | 必需 | 1.20.4+ |
| Vault | 推荐 | - |
| HumanVerify | 可选 | - |
| CMI | 可选 | - |

## 安装

1. 下载 `LetMeAsk-2.0.0.jar`（或 CI `latest` 滚动构建）
2. 将 JAR 文件放入服务器 `plugins/` 目录
3. 重启服务器
4. 编辑 `plugins/LetMeAsk/base.yml` 和 `questions.yml` 进行配置

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/letmeask` 或 `/letmeask help`（简写 `/lma`） | 全员 | 帮助菜单（管理员额外显示管理命令） |
| `/letmeask top [数量]` | 全员 | 答题排行榜（默认前 10，最多 20） |
| `/letmeask stats [玩家名]` | 全员 | 查看答题统计（默认查自己） |
| `/letmeask status` | 全员 | 查看插件状态 |
| `/letmeask start` | letmeask.admin | 启动定时出题 |
| `/letmeask stop` | letmeask.admin | 停止定时出题（同时作废当前题目并解锁验证） |
| `/letmeask question [force]` | letmeask.admin | 手动发布新题目 |
| `/letmeask reload` | letmeask.admin | 重载配置文件 |

所有子命令均支持简写 `/lma`（如 `/lma top`、`/lma stats`）。
排行榜会按 `leaderboard-broadcast` 配置定时在游戏内全局广播（默认每小时前 10 名，`enabled: false` 可关闭）。

## 配置

### base.yml

```yaml
# 扣款玩家（支持: 玩家名、UUID、littleskin:xxx、Server/Console）
payer: Server

# 每题奖励金额
reward: 50.0

# 自动出题间隔（秒）
question-interval-seconds: 60

# 答题超时时间（秒），超时后公布答案并出下一题（0=禁用）
question-timeout-seconds: 30

# 回答用时阈值（秒，支持小数），过快触发人机验证
anti-bot-threshold-seconds: 1

# 连续答对次数阈值，达到触发人机验证（0=禁用）
anti-bot-correct-answer-threshold: 3

# 防脚本枚举：本轮题目期间最近 N 条消息中任一相邻间隔低于该秒数则直接踢出（秒数 0=禁用）
anti-bot-chat-history-count: 3
anti-bot-chat-min-interval-seconds: 0.5

# 连击统计时间窗口（秒），超时未答对则连击清零
anti-bot-streak-window-seconds: 300

# 人机验证超时（秒），回调长时间未返回时自动解锁并作废本轮
verify-timeout-seconds: 120

# 资金不足暂停后，每隔多少秒复查一次出资人余额（最小 5 秒）
balance-retry-seconds: 30

# 答案模糊匹配阈值（0-1，越高越严格；1.0=精确匹配）
fuzzy-similarity-threshold: 0.75

# 定时广播排行榜（enabled=false 关闭；minutes 最小 1；count 最多 20）
leaderboard-broadcast:
  enabled: true
  minutes: 60
  count: 10

# 答对庆祝：Title 只发给答对者，音效全服可听（sound=none 关闭音效）
celebrate:
  enabled: true
  title: "&6&l答对了！"
  subtitle: "&e+{reward} 金币"
  sound: ENTITY_PLAYER_LEVELUP
  volume: 1.0
  pitch: 1.0

# 物品奖励：与金币叠加发放，背包满掉在脚下
# random-one: true=每次随机其一，false=全发；name/lore/enchantments 可选
items:
  enabled: false
  random-one: false
  list:
    - {material: DIAMOND, amount: 2}
    - {material: GOLD_INGOT, amount: 1, name: "&6幸运金锭", lore: ["&7答题奖励"], enchantments: {luck: 1}}

# 消息前缀
messages:
  prefix: "&6[教育部]"
  # ...（查询/管理/游戏流程/帮助/状态共 60+ 键，见 base.yml）
```

### 多语言（`language`）

```yaml
# zh=中文（默认），en=英文
language: zh
```

设为 `en` 时所有文案读 `messages-en` 段（58 个键，与中文一一对应），
缺键自动回退中文，所以老服升级无感——`messages-en` 整段缺失也能跑。
`status` 页的是/否/运行中走 `common-yes/no/running/stopped/none` 通用键，同样中英切换。

### messages 全键表

所有用户可见文案都可在 `messages` 下自定义，支持 `&` 颜色码。
占位符：`{arg}` 通用参数、`{arg2}` 第二参数、`{cmd}` 命令别名（如 `lma`，仅帮助菜单用）。

| 键 | 说明 |
|----|------|
| `prefix` | 所有广播的前缀 |
| `console-need-name` / `player-not-found` / `invalid-count` / `no-records` | 查询类提示 |
| `unknown-command` / `no-permission` | 命令错误提示 |
| `started` / `stopped` / `stopped-with-question` | 启停反馈 |
| `question-posted` / `question-blocked` | 出题反馈 |
| `reloaded` / `reloaded-empty` / `reload-dropped-verify` | 重载反馈 |
| `kick-verify-timeout` / `kick-chat-too-fast` / `kick-verify-failed` | 踢人原因（显示在被踢画面） |
| `announce-question` | 新题广播，`{arg}`=题目 |
| `announce-funded` | 余额恢复广播，`{arg}`=余额、`{arg2}`=可奖励次数 |
| `announce-verify-timeout-kick` / `announce-verify-timeout` | 验证超时广播 |
| `announce-timeout` | 无人答对广播，`{arg}`=答案 |
| `announce-chat-kick` | 刷屏踢人广播，`{arg}`=玩家名 |
| `announce-verify-start` | 验证开始广播，`{arg}`=玩家名、`{arg2}`=原因 |
| `announce-verify-failed` | 验证失败广播，`{arg}`=玩家名 |
| `announce-win-no-vault` / `announce-win` / `announce-win-self` | 答对广播（无经济/零奖励/出资人自答），`{arg}`=玩家名 |
| `announce-win-reward` | 正常发奖广播，`{arg}`=玩家名、`{arg2}`=金额 |
| `announce-win-items` | 物品奖励后缀，`{arg}`=物品描述（如"2x Diamond"） |
| `announce-paused-funds` | 资金不足暂停，`{arg}`=需用、`{arg2}`=当前 |
| `announce-transfer-failed` / `announce-refunded` / `announce-refund-failed` | 转账失败相关，`{arg}`=错误信息 |
| `help-*` | 帮助菜单 11 行，`{cmd}`=实际输入的别名 |
| `status-*` | 状态页 7 行 |

注意：老服 `base.yml` 不会被新默认值覆盖（`saveResource` 只写不存在的文件），缺键时走代码内置默认值。

### questions.yml

```yaml
# 纯字符串格式：题目=答案（多答案用 | 分隔，答对任一即可）
# map 格式：{q, a, weight}，权重越大越容易被抽中（默认 1，范围 1~10000）
questions:
  - "中国首都=北京"
  - "2+2=4"
  - "香蕉是什么颜色=黄色|黄"
  - {q: "太阳系最大的行星是什么", a: "木星|Jupiter", weight: 2}
```

### stats.yml（自动生成）

```yaml
total-asked: 1234        # 累计出题数
total-answered: 567      # 累计答对数
players:
  <UUID>:
    correct: 10          # 该玩家答对次数
    earned: 500.0        # 该玩家累计奖金
    name: "Steve"        # 最后已知玩家名（top 榜显示用）
```

30 秒增量落盘（无变更不写文件），约 5 分钟全量一次，关服时全量保存。
手动改 `stats.yml` 后需 `/lma reload`？**不需要**——统计只在启动时加载，运行时改文件会被下次落盘覆盖。
想清榜请停服后删文件再开服。

## 构建

使用 Maven（推荐）：

```bash
mvn clean package
```

或使用 Gradle（依赖 `libs/` 目录下的本地 jar，可离线构建）：

```bash
gradle build
```

### 版本管理

版本号唯一来源是 `pom.xml` 的 `<revision>`，`plugin.yml` 与产物 jar 名构建时自动跟随，**发版只改这一处**：

```bash
mvn clean package -Drevision=1.2.0
```

CI 每次构建会自动追加 commit 短哈希（如 `2.0.0-a1b2c3d`），`latest` release 永远是最新构建。

详见 [CHANGELOG.md](CHANGELOG.md)。

## 常见问题

**Q：没装 Vault/HumanVerify 能用吗？**
A：能。无 Vault 时纯公告无奖励；无 HumanVerify 时触发验证直接发奖（缺失告警只刷一次）。

**Q：出了题没人答对怎么办？**
A：`question-timeout-seconds` 超时后自动公布答案并出下一题，设 `0` 禁用超时。

**Q：出题暂停了怎么恢复？**
A：出资人余额不足时自动暂停，按 `balance-retry-seconds`（默认 30s）复查，充钱后自动恢复。余额恰好等于奖励时多留 1 美分再恢复，避免横跳。

**Q：答对了没到账？**
A：先看控制台有无"扣款失败/发放失败"日志。发放失败会自动退款；退款也失败会刷 severe 日志并公告"手动补账"，按日志里的出资人/玩家/金额手工处理。

**Q：改了 base.yml 没生效？**
A：改完执行 `/lma reload`。注意题库为空时 reload 整体回滚（旧配置不动），会提示"已保留旧题库与旧配置"。

**Q：排行榜是空的还每小时广播？**
A：不会。空榜时定时广播自动跳过，手动 `/lma top` 仍提示"暂无答题记录"。

**Q：玩家改名了统计会丢吗？**
A：不会。统计 key 是 UUID，名只做显示；改名后下次答对自动更新。

## 许可证

MIT License
