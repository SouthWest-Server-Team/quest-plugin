package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class QuestScheduler implements Runnable {

    private final QuestPlugin plugin;
    private final QuestDataManager dataManager;
    private BukkitTask task;

    public QuestScheduler(QuestPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 600L, 600L); // 30s
    }

    public void stop() { if (task != null) task.cancel(); }

    @Override
    public void run() {
        List<Quest> all = new ArrayList<>(dataManager.loadAll());
        boolean changed = false;

        for (int i = 0; i < all.size(); i++) {
            Quest q = all.get(i);
            if (q.isExpired()) {
                Quest expired = q.cancel();
                all.set(i, expired);
                changed = true;

                // Notify parties
                notify(q.publisherId(), "§c[委托] §6" + q.title() + " §c已过期取消。");
                if (q.acceptorId() != null) {
                    notify(q.acceptorId(), "§c[委托] §6" + q.title() + " §c已过期取消。");
                    // Remove scroll from online acceptor
                    Player acc = Bukkit.getPlayer(q.acceptorId());
                    if (acc != null) QuestScroll.removeFromInventory(acc, q.id(), plugin);
                }

                // Apply penalty
                if (q.status() == QuestStatus.OPEN) {
                    // Return deposit
                    var econ = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                    if (econ != null) econ.getProvider().depositPlayer(Bukkit.getOfflinePlayer(q.publisherId()), q.deposit() + q.reward());
                }
                if (q.status() == QuestStatus.ACCEPTED && q.acceptorId() != null) {
                    // Register violation for acceptor（经交互层取城邦，不直连 Towny 内部类）
                    String townName = acceptorTownName(q);
                    if (townName != null) plugin.getViolationManager().addViolation(townName);

                    var econ = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                    if (econ != null) {
                        double penalty = q.reward() * plugin.getConfig().getDouble("cancel-penalty-acceptor", 3.0);
                        econ.getProvider().withdrawPlayer(Bukkit.getOfflinePlayer(q.acceptorId()), penalty);
                        econ.getProvider().depositPlayer(Bukkit.getOfflinePlayer(q.publisherId()), penalty + q.deposit() + q.reward());
                        econ.getProvider().depositPlayer(Bukkit.getOfflinePlayer(q.acceptorId()), q.deposit());
                    }
                }
            }
        }

        if (changed) dataManager.saveAll(all);

        // Update scroll lore for online players
        updateScrollLore(all);
    }

    private void updateScrollLore(List<Quest> all) {
        QuestScroll scroll = new QuestScroll(plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                var item = player.getInventory().getItem(i);
                if (!scroll.isScroll(item)) continue;
                UUID qid = scroll.getQuestId(item);
                if (qid == null) continue;
                all.stream().filter(q -> q.id().equals(qid)).findFirst()
                        .ifPresent(q -> scroll.updateLore(item, q));
            }
        }
    }

    private void notify(java.util.UUID uuid, String msg) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(msg);
    }

    /**
     * 违约记账用的城邦名（语义与改造前一致：接取方所属城邦）。
     *
     * <p>优先经交互层查接取方此刻的城邦；查不到（玩家离线、交互层不可用）但这条城邦委托有归属城邦时，
     * 退回用委托自己的归属城邦名 —— 改造前 {@code TownyAPI.getTown(UUID)} 对离线玩家也能解析，
     * 这样离线接取方的违约不会被漏记。
     */
    private String acceptorTownName(Quest q) {
        var view = com.xinantown.quest.town.TownQueryBridge.viewOf(plugin.getTownQueryBridge(), q.acceptorId());
        if (view.hasTown()) return view.townName();
        if (q.isTownQuest() && q.townName() != null && !q.townName().isBlank()) return q.townName();
        return null;
    }
}
