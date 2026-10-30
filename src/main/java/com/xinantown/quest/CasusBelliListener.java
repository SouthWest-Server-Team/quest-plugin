package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import com.xinantown.quest.war.WarInfoBridge;
import com.xinantown.quest.war.WarViolationDetector;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * CasusBelli 违约检测：已接取的城邦委托双方若处于 ACTIVE 战争，只对宣战方记一次违约。
 *
 * <p>数据来源改为交互层只读契约（{@link WarInfoBridge} → WarInfoProvider），不再反射直读 War 插件内部对象。
 *
 * <p>调度方式（改造前是 600 tick 无条件全量轮询）：
 * <ol>
 *   <li><b>由委托数据变更唤醒</b>：{@link QuestDataManager#setChangeListener} 挂在 {@code saveAll} 上，
 *       而 quest-plugin 里所有委托状态变更都必然经过 {@code saveAll}（发布/接取/提交/完成/取消/驳回/过期），
 *       因此这是本插件「委托事实发生变化」的完整信号；
 *       之所以不用 Bukkit 的 {@code QuestAcceptedEvent}，是因为 {@code /quest accept} 命令路径并不触发它，
 *       只监听事件会漏掉委托。</li>
 *   <li><b>没有可判定委托时不轮询</b>：对账发现「没有任何已接取的城邦委托」就立刻取消定时任务——
 *       此时不可能产生违约，等下一次委托变更再唤醒。</li>
 *   <li><b>唤醒后是电平式对账</b>：按配置间隔重新读一次当前战争状态并判定。
 *       电平式对账不会漏掉任何状态变化（只影响发现延迟），而违约的惩罚粒度是「天」，
 *       所以间隔从 30 秒放宽到 60 秒（{@code war-violation-check-seconds} 可配），
 *       代价只是发现延迟，收益是轮询次数减半且与战争/委托数量无关。</li>
 *   <li>交互层 provider 不可用时根本不排定任务（等价于改造前「War 插件未加载则关闭集成」）。</li>
 * </ol>
 *
 * <p>每次对账只向 provider 取一次快照，并且只对「已接取的城邦委托」做 Towny 城镇解析
 * （同一轮内按玩家 UUID 去重），不再对全部委托做 {@code Bukkit.getOfflinePlayer} + Towny 查询。
 */
public class CasusBelliListener {

    /** 对账间隔（秒）。电平式对账，不会漏掉状态变化，只影响发现延迟。 */
    private static final int DEFAULT_CHECK_SECONDS = 60;

    private final QuestPlugin plugin;
    private final QuestDataManager dataManager;
    private final ViolationManager violationManager;
    private final Logger logger;
    private final WarInfoBridge warInfo;
    private final long intervalTicks;

    private BukkitTask task;
    private boolean unavailableReported;

    public CasusBelliListener(QuestPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
        this.violationManager = plugin.getViolationManager();
        this.logger = plugin.getLogger();
        this.intervalTicks = Math.max(1,
                plugin.getConfig().getInt("war-violation-check-seconds", DEFAULT_CHECK_SECONDS)) * 20L;
        this.warInfo = createBridge(plugin);
    }

    /**
     * 建立交互层桥接；运行环境没有交互层时降级为 null（违约检测关闭，委托功能不受影响）。
     */
    private static WarInfoBridge createBridge(QuestPlugin plugin) {
        try {
            return new WarInfoBridge(plugin.getServer().getServicesManager(), plugin.getLogger(),
                    new WarViolationDetector());
        } catch (LinkageError | RuntimeException failure) {
            plugin.getLogger().warning("XiNanTown 交互层不可用，CasusBelli 违约检测停用: " + failure);
            return null;
        }
    }

    /**
     * 挂上委托数据变更回调，并对服务端启动时既有的已接取城邦委托做一次对账。
     */
    public void register() {
        dataManager.setChangeListener(this::arm);
        arm();
    }

    /**
     * 停止轮询并摘掉回调（插件卸载时调用）。
     */
    public void stop() {
        disarm();
        dataManager.setChangeListener(null);
    }

    /**
     * 唤醒对账。已有任务或交互层不可用时什么都不做。
     */
    private void arm() {
        if (task != null || warInfo == null) {
            return;
        }
        if (!warInfo.isAvailable()) {
            if (!unavailableReported) {
                unavailableReported = true;
                logger.info("战争信息 provider 不可用，CasusBelli 违约检测暂不轮询；"
                        + "交互层可用后由下一次委托变更自动恢复。");
            }
            return;
        }
        unavailableReported = false;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::reconcile, intervalTicks, intervalTicks);
    }

    private void disarm() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 一轮对账：收集可判定的委托 → 取一次战争快照 → 判定 → 记账。
     *
     * <p>整轮兜底捕获异常：Bukkit 的重复任务一旦抛异常就会被取消，那会静默丢掉后续所有检测。
     */
    private void reconcile() {
        try {
            List<WarViolationDetector.Candidate> candidates = new ArrayList<>();
            int watchableQuests = 0;
            // 同一轮内按玩家 UUID 缓存城镇解析结果，避免对同一玩家重复查询 Towny
            Map<UUID, String> townNames = new HashMap<>();

            for (Quest q : new ArrayList<>(dataManager.loadAll())) {
                if (!isWatchableTownQuest(q)) continue;
                watchableQuests++;
                try {
                    String publisherTown = townNameOf(q.publisherId(), townNames);
                    String acceptorTown = townNameOf(q.acceptorId(), townNames);
                    if (publisherTown == null || acceptorTown == null) {
                        continue; // 城镇本轮解析不出来（例如玩家已不在任何城镇），下一轮再试
                    }
                    candidates.add(new WarViolationDetector.Candidate(q.id(), q.title(),
                            publisherTown, acceptorTown));
                } catch (RuntimeException | LinkageError ignored) {
                    // 单条委托解析失败不影响其它委托
                }
            }

            if (watchableQuests == 0) {
                // 没有任何已接取的城邦委托 → 不可能产生违约，停止轮询，等下一次委托变更唤醒
                disarm();
                return;
            }
            if (candidates.isEmpty()) {
                return; // 有委托但双方城镇暂不可解析：保持轮询，下一轮再试
            }

            for (WarViolationDetector.Violation violation : warInfo.detect(candidates)) {
                violationManager.addViolation(violation.attackerTown());
                logger.info("Violation recorded only for declaring town " + violation.attackerTown()
                        + " due to war during quest " + violation.questTitle());
            }
        } catch (RuntimeException | LinkageError failure) {
            logger.warning("CasusBelli 违约检测本轮失败，已跳过: " + failure);
        }
    }

    /**
     * 筛选条件与改造前一致：已接取（ACCEPTED）的城邦委托，且已有接取方。
     */
    private static boolean isWatchableTownQuest(Quest q) {
        return q.status() == QuestStatus.ACCEPTED
                && q.isTownQuest()
                && q.acceptorId() != null;
    }

    /**
     * 玩家 UUID → 城镇名（Towny 的职责，与战争数据来源无关）。
     * 直接按 UUID 查询，不再绕 {@code Bukkit.getOfflinePlayer}。
     */
    private String townNameOf(UUID playerId, Map<UUID, String> cache) {
        if (playerId == null) {
            return null;
        }
        if (cache.containsKey(playerId)) {
            return cache.get(playerId);
        }
        // 城邦名经交互层只读能力获取（不再直连 Towny 内部类）。
        var view = com.xinantown.quest.town.TownQueryBridge.viewOf(plugin.getTownQueryBridge(), playerId);
        String name = view.hasTown() ? view.townName() : null;
        cache.put(playerId, name);
        return name;
    }
}
