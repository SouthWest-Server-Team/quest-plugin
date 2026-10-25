# AGENTS.md — quest-plugin

> 本文件由 `chore/agents-md-20261025` 引入，事实来源为 `origin/debug` 提交 `d3e7da0`（本地 `debug` 可能落后）的实机采集（源码 + 真实 `mvn` 输出）。

## 一句话职责

城邦委托系统：材料 / 建筑委托的发布—接单—交付—结算，配套共享仓库、物理委托栏（悬浮展示）与违约记录。委托结算规则集中在 `SettlementRules`（已特性化测试锁定）。

## 模块与主类

单模块 Maven 工程（无 `<modules>`），含 `mvnw.cmd`；`src/main/java` 下 24 个类（全部平铺在 `com.xinantown.quest`，仅 `model/` 与 `event/` 有子包）：

| 关注点 | 类 |
|---|---|
| 生命周期 | `QuestPlugin`、`QuestCommand`、`QuestGuiManager`、`QuestScheduler`、`QuestScroll` |
| 领域模型 | `model/Quest`、`QuestStatus`、`QuestFilter`、`Board` |
| 结算 / 违约 | `SettlementRules`、`ViolationManager`、`CasusBelliListener` |
| 委托栏 | `BoardManager`、`BoardMenuHandler`、`BoardAcceptHandler`、`BoardDisplayManager`、`BoardListener` |
| 共享仓库 | `WarehouseManager` |
| 事件（对外） | `event/QuestCreatedEvent`、`QuestAcceptedEvent`、`QuestCompletedEvent`、`QuestEvent` |

- 主类：`src/main/java/com/xinantown/quest/QuestPlugin.java`
- `plugin.yml`：`depend: [Vault, Towny]`，`softdepend: [War]`；命令 `/quest`。

## 构建与测试（2026-10-25 实测）

```bash
mvn -o -B -nsu clean test   # JDK 21；本机无 mvn 在 PATH，用 ~/.m2/wrapper 的 3.9.6 直启
```

- 结果：**Tests run: 19, Failures: 0, Errors: 0, Skipped: 0**，`BUILD SUCCESS`（3.5 s）。
- 分布：`QuestTest` 9、`SettlementRulesCharacterizationTest` 8、`QuestDataManagerTest` 2。
- 依赖：`leaf-api 1.21.11-R0.1-SNAPSHOT`、`towny 0.103.1.0`、`VaultAPI 1.7`、JUnit 5.10.2；`snakeyaml` 只在 test scope。

## 对外契约与消费方

- 主契约是 Bukkit 事件总线：`QuestCreatedEvent` / `QuestAcceptedEvent` / `QuestCompletedEvent`（均继承 `QuestEvent`），供展示、统计、理念 / 商会类插件订阅。
- 与 `war-plugin` 的耦合是**反射**：`CasusBelliListener` 在运行时探测 CasusBelli 事件类，**编译期不依赖 War 实现类**，找不到就只打一行日志并禁用该集成。保持这个方向，别改成硬 import。无 `xinantown-api` 依赖，也无 `ServiceManager` 注册。

## 数据与配置落盘

`plugins/Quest/` 下（均在 `QuestPlugin.onEnable` 装配）：

- `quests.yml` — 委托本体（`QuestDataManager`）
- `warehouses/` — 每仓一个文件的共享仓库（`WarehouseManager`）
- `violations.yml` — 违约 / 拒单记录（`ViolationManager`）
- `boards.yml` — 物理委托栏位点（`BoardManager`）
- `config.yml` 关键项：`max-rejects` 5、`cancel-penalty-publisher` 0.5、`cancel-penalty-acceptor` 3.0、`accept-deadline-days` 7、`complete-deadline-days` 7、`board-rotation-seconds` 30、`board-urgent-hours` 1、`board-warn-hours` 24、`board-urgent-copies` 3、`board-warn-copies` 2。

## 红线

- 现有结算**直接调 Vault `Economy`**（`BoardAcceptHandler` / `BoardMenuHandler` / `QuestCommand` / `QuestGuiManager`），未走 Transaction-Bridge —— 这是既有债务，**新代码不得再扩大**直连面。
- 不得为「能编译」而引入 `war-plugin` 实现类依赖；War 集成只走反射 / 事件。
- `SettlementRules` 是结算口径的唯一落点，改动必须同时补 `SettlementRulesCharacterizationTest`，禁止删测试换绿。
- 覆盖测试服 jar 只在停服时做；提交走 PR（base `debug`），合并归搭档，不直推 `main`。

## 必读文档

- 仓内：`README.md`。
- KB：`facts/quest-plugin/quest-lifecycle-and-settlement.md`、`quest-persistence-and-warehouse.md`。
