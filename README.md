# quest-plugin — 委托系统

Towny + CasusBelli + Vault 集成插件。玩家和城邦发布资源委托，其他玩家/城邦接取完成。

## 功能规划

- 资源委托：物品ID×数量的交付任务
- 个人委托：个人发布，个人或城邦接取
- 城邦委托：城邦发布，仅城邦接取
- 共享仓库：ChestMenu GUI，接取方操作、发布方查看
- 取消惩罚：发布方 0.5× / 接取方 3×（可配）
- 驳回重试：累计 N 次（可配，默认 5）后可终止
- 违约联动：合作期间宣战→违约，累计 1天×N（CasusBelli 集成）
- 超时违约：期限内未完成→违约+赔付 3×

## 开发状态

🟡 设计阶段 — 见 `docs/adr/0004-quest-plugin.md`

## 技术栈

- Java 17 + Maven
- Paper API 1.20.1
- Towny API + CasusBelli API + Vault API
- JUnit 5
