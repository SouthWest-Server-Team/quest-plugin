package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

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
                    // Register violation for acceptor
                    var town = com.palmergames.bukkit.towny.TownyAPI.getInstance().getTown(
                            Bukkit.getOfflinePlayer(q.acceptorId()).getUniqueId());
                    if (town != null) plugin.getViolationManager().addViolation(town.getName());

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
    }

    private void notify(java.util.UUID uuid, String msg) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(msg);
    }
}
