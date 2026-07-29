# QuestPlugin — 委托系统

Towny + CasusBelli + Vault 集成委托系统。玩家和城邦发布材料/建筑委托，通过物理告示牌（委托栏）或命令行交互完成接取、提交、审核全流程。

## 功能

- **材料委托**：发布方提供材料描述+数量，接收方交付实物物品
- **建筑委托**：发布方提供建筑描述，接收方施工完成后提交
- **个人委托**：玩家发布，个人或城邦接取
- **城邦委托**：市长发布，仅城邦市长接取
- **委托栏**：物理告示牌交互——管理员放置中央/显示告示牌，玩家右键操作
- **聊天引导发布**：点击[发布委托]→逐步输入材料/建筑描述→报酬→押金→确认创建
- **委托卷**：接取后获得 BOOK，右键打开共享仓库 GUI
- **共享仓库**：54 格 GUI，接收方放物品/提交，发布方查看/审核
- **同意确认**：发布方点击"同意"需二次确认
- **驳回理由**：点击"驳回"→聊天栏输入理由→通知接收方
- **轮播显示**：显示告示牌每 30s 轮播可接取委托，相邻告示牌联动分组
- **取消惩罚**：发布方 0.5× / 接取方 3×（可配）
- **违约联动**：CasusBelli 宣战→双方违约，累计 1天×N
- **超时违约**：期限内未完成→违约+赔付 3×

## 委托生命周期

```
创建(告示牌右键) → 等待接取 → 接取(获卷) → 执行中(仓库操作)
    → 提交 → 同意(完成) / 驳回(返回执行)
    → 累计 N 次驳回 → 允许终止
```

## 指令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/quest list` | 查看可接取委托 | `quest.use` |
| `/quest accept <ID>` | 命令行接取委托 | `quest.use` |
| `/quest warehouse <ID>` | 打开委托仓库 | `quest.use` |
| `/quest my` | 查看我发布的委托 | `quest.use` |
| `/quest board create center\|display` | 创建委托栏告示牌 | `quest.admin` |
| `/quest board remove` | 移除委托栏告示牌 | `quest.admin` |

## 委托栏交互

| 告示牌 | 材质 | 右键行为 |
|--------|------|----------|
| 中央告示牌 | 橡木告示牌 + 全息浮标 | 聊天栏按钮：发布委托 / 我的委托 |
| 显示告示牌 | 橡木告示牌 + 全息浮标 | 显示委托信息→5s内再次右键确认接取 |

- 相邻显示告示牌自动分组轮播（BFS 涟漪检测，每 30s 重算）
- 告示牌防破坏（需 `/quest board remove` 移除）

## 共享仓库 GUI

| 按钮 | 触发条件 | 行为 |
|------|----------|------|
| 提交委托 | 接收方 + ACCEPTED | 锁定仓库，通知发布方 |
| 暂存 | 接收方 + ACCEPTED | 保存当前进度 |
| 同意 | 发布方 + SUBMITTED | 二次确认→发放物品+报酬 |
| 驳回 | 发布方 + SUBMITTED | 聊天输入理由→通知接收方 |
| 终止 | 发布方 + 驳回≥上限 | 退还双方押金 |
| 取消 | 双方 | 撤回/取消（带惩罚） |

## 配置

```yaml
# config.yml
max-rejects: 5                  # 最大驳回次数
cancel-penalty-publisher: 0.5   # 发布方取消罚款倍率
cancel-penalty-acceptor: 3.0    # 接收方取消罚款倍率
accept-deadline-days: 7         # 接取期限
complete-deadline-days: 7       # 完成期限
board-rotation-seconds: 30      # 显示告示牌轮播间隔
```

## 架构

```
QuestPlugin (入口)
  ├── BoardMenuHandler       — 中央告示牌聊天菜单 + 聊天引导发布
  ├── BoardDisplayManager    — 显示告示牌轮播 + 分组 + boardQuestMap
  ├── BoardAcceptHandler     — 接取确认 + 委托卷发放 + 右键仓库
  ├── BoardListener          — 告示牌防破坏 + 全息浮标
  ├── BoardManager           — 告示牌 CRUD + 分组 BFS
  ├── QuestCommand           — 命令行
  ├── QuestGuiManager        — 共享仓库 GUI + 聊天监听(驳回理由)
  ├── QuestScheduler         — 过期检测 + 超时违约
  ├── QuestDataManager       — YAML 持久化
  ├── ViolationManager       — 违约累计 + 封禁
  ├── CasusBelliListener     — 宣战→违约联动
  ├── WarehouseManager       — 仓库物品持久化
  └── QuestScroll            — 委托卷(BOOK) + removeFromInventory

model/
  ├── Quest                  — 委托 record
  ├── QuestStatus            — OPEN/ACCEPTED/SUBMITTED/COMPLETED/CANCELLED
  └── Board                  — 告示牌 record
```

## 依赖

| 插件 | 必需 | 说明 |
|------|------|------|
| Towny | ✅ | 城邦/市长数据源 |
| Vault | ✅ | 经济系统 |
| CasusBelli | 可选 | 宣战违约联动 |

## 构建

```bash
# JDK 17+
mvnw clean package
# 输出: target/Quest-1.0.0-SNAPSHOT.jar
```

## ADR

- [ADR-0004](docs/adr/0004-quest-plugin.md) — 委托系统初始设计
- [ADR-0005](docs/adr/0005-quest-board.md) — 委托栏物理交互系统
- [ADR-0006](docs/adr/0006-quest-workflow.md) — 委托工作流完善
