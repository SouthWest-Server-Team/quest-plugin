# QuestPlugin — 委托系统

Towny + CasusBelli + Vault 集成委托系统。玩家和城邦发布材料/建筑委托，通过物理告示牌（委托栏）或命令行交互完成接取、提交、审核全流程。

## 功能

- **材料委托**：发布方提供材料描述+数量，接收方交付实物物品
- **建筑委托**：发布方提供建筑描述，接收方施工完成后提交
- **个人委托**：玩家发布，个人或城邦接取
- **城邦委托**：市长发布，仅城邦市长接取
- **委托栏**：物理告示牌交互——管理员放置中央/显示告示牌，支持类型过滤（个人/城邦/全部）
- **聊天引导发布**：点击[发布委托]→选择类型→4步逐步输入→确认创建
- **显示告示牌**：30s 轮播 + 相邻 BFS 分组 + 全局去重（同一委托仅一块牌）
- **热卖加权**：即将超时委托展示频率更高（可配置阈值和副本数）
- **事件驱动刷新**：委托创建/接取/完成时自动刷新所有告示牌
- **告示牌荧光**：所有委托栏告示牌文字发光效果
- **委托卷**：接取后获得 BOOK，右键打开仓库，Lore 实时显示状态+剩余时间
- **`/quest my`**：按状态排序（待确认→进行中→可接取→已完成），可点击直接打开仓库
- **提交通知**：发布方收到可点击链接直接打开仓库确认
- **共享仓库**：54 格 GUI，完成/取消后自动关闭仓库防误操作
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
| `/quest my` | 查看我发布的委托（按状态排序+可点击打开仓库） | `quest.use` |
| `/quest board create center` | 创建中央告示牌（发布入口） | `quest.admin` |
| `/quest board create display` | 创建显示告示牌（全部委托） | `quest.admin` |
| `/quest board create display personal` | 创建显示告示牌（仅个人委托） | `quest.admin` |
| `/quest board create display town` | 创建显示告示牌（仅城邦委托） | `quest.admin` |
| `/quest board remove` | 移除对准的委托栏告示牌 | `quest.admin` |

> 委托发布不通过命令行，改为右键中央告示牌 → 聊天栏按钮逐步引导（材料/建筑/城邦材料/城邦建筑 4 种类型）。

## 委托栏交互

| 告示牌 | 材质 | 右键行为 |
|--------|------|----------|
| 中央告示牌 | 橡木告示牌（荧光） | 聊天栏按钮：发布委托 / 我的委托 |
| 显示告示牌 | 橡木告示牌（荧光） | 显示委托信息→5s内再次右键确认接取 |

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
board-rotation-seconds: 30      # 轮播间隔
board-urgent-hours: 1           # 热卖: 紧急阈值(小时)
board-warn-hours: 24            # 热卖: 预警阈值(小时)
board-urgent-copies: 3          # 热卖: 紧急副本数
board-warn-copies: 2            # 热卖: 预警副本数
```

## 架构

```
QuestPlugin (入口)
  ├── BoardMenuHandler       — 中央告示牌聊天菜单 + 聊天引导发布
  ├── BoardDisplayManager    — 显示告示牌轮播 + 分组 + 全局去重 + 热卖加权
  ├── BoardAcceptHandler     — 接取确认 + 委托卷发放 + 右键仓库
  ├── BoardListener          — 告示牌防破坏 + 全息浮标 + 重启恢复
  ├── BoardManager           — 告示牌 CRUD + 分组 BFS
  ├── DisplayUpdateListener  — 事件驱动刷新（监听 QuestEvent 自动更新告示牌）
  ├── QuestCommand           — 命令行 + `/quest my` 排序+可点击
  ├── QuestGuiManager        — 共享仓库 GUI + 聊天监听(驳回理由) + 完成守护
  ├── QuestScheduler         — 过期检测 + 超时违约 + 卷轴 Lore 实时更新
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
