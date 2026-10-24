# 安全策略

## 报告漏洞

请通过 GitHub 仓库的 **Security → Report a vulnerability**（Security Advisory）私下提交，不要公开开 issue。

报告请尽量包含：

- 受影响版本（`/letmeask status` 或 jar 文件名中的版本号）
- 复现步骤与环境（服务端类型/版本、是否安装 Vault / HumanVerify）
- 影响范围（如：经济刷取、权限绕过、信息泄露、拒绝服务）

修复发布前请勿公开细节。

## 支持范围

仅最新滚动构建（`latest` release / `main` 分支）接受安全修复。

## 部署建议

- 不要在未安装 HumanVerify 的奖励服保留 `anti-bot-verify-unavailable: award`；
  默认的 `deny` 会在验证服务缺失时拒绝发奖，避免脚本绕过反作弊。
- 可使用权限插件把 `letmeask.stats.others` 设为 `false`，限制玩家查询他人统计。
- 定期备份 `plugins/LetMeAsk/` 下的 `base.yml`、`questions.yml` 与 `stats.yml`。
