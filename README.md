# LetMeAsk

一个 Minecraft 服务器抢答活动插件，定时在聊天栏出题，玩家抢答可获得金币奖励。

## 功能特性

- **定时自动出题**：按照配置间隔自动发布题目
- **答题超时**：超时无人答对时自动公布答案并出下一题
- **经济系统集成**：支持 Vault 经济插件，自动扣款/发放奖励
- **模糊匹配**：答案支持容错匹配（拼写相似度可配置）
- **人机验证**：答题过快或连续答对过多时触发 HumanVerify 验证
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

1. 下载 `QuizPlugin-1.0.0.jar`
2. 将 JAR 文件放入服务器 `plugins/` 目录
3. 重启服务器或执行 `/reload`
4. 编辑 `plugins/QuizPlugin/base.yml` 和 `questions.yml` 进行配置

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/letmeask start` | letmeask.admin | 启动定时出题 |
| `/letmeask stop` | letmeask.admin | 停止定时出题 |
| `/letmeask question [force]` | letmeask.admin | 手动发布新题目 |
| `/letmeask reload` | letmeask.admin | 重载配置文件 |
| `/letmeask status` | letmeask.admin | 查看插件状态 |

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

# 回答速度阈值（秒），过快触发人机验证
anti-bot-threshold-seconds: 1

# 连续答对次数阈值，达到触发人机验证（0=禁用）
anti-bot-correct-answer-threshold: 3

# 答案模糊匹配阈值（0-1，越高越严格）
fuzzy-similarity-threshold: 0.75

# 消息前缀
messages:
  prefix: "&6[教育部]"
```

### questions.yml

```yaml
# 格式：题目=答案（每行一条）
questions:
  - "中国首都=北京"
  - "2+2=4"
  - "香蕉是什么颜色=黄色"
```

## 构建

使用 Maven（推荐）：

```bash
mvn clean package
```

或使用 Gradle（依赖 `libs/` 目录下的本地 jar，可离线构建）：

```bash
gradle build
```

## 许可证

MIT License
