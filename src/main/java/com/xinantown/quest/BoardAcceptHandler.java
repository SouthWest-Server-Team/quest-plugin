package com.xinantown.quest;

import com.xinantown.quest.model.Board;
import com.xinantown.quest.model.QuestStatus;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Handles quest acceptance via display board right-click,
 * scroll issuance, and scroll right-click to open warehouse.
 */
public class BoardAcceptHandler implements Listener {

    private final QuestPlugin plugin;
    private final BoardManager boardManager;
    private final BoardDisplayManager displayManager;
    private final QuestDataManager dataManager;
    private Economy econ;
    private final Map<UUID, PendingAccept> pendingAccepts = new HashMap<>();

    record PendingAccept(UUID questId, long expireTime) {}

    public BoardAcceptHandler(QuestPlugin plugin, BoardManager boardManager,
                               BoardDisplayManager displayManager, QuestDataManager dataManager) {
        this.plugin = plugin;
        this.boardManager = boardManager;
        this.displayManager = displayManager;
        this.dataManager = dataManager;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) this.econ = rsp.getProvider();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Scroll right-click → open warehouse
        QuestScroll scroll = new QuestScroll(plugin);
        ItemStack item = event.getItem();
        if (item != null && scroll.isScroll(item)
                && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            UUID questId = scroll.getQuestId(item);
            if (questId != null) {
                var quest = dataManager.loadAll().stream()
                        .filter(q -> q.id().equals(questId)).findFirst().orElse(null);
                if (quest != null) {
                    event.setCancelled(true);
                    plugin.getGuiManager().openWarehouse(event.getPlayer(), quest);
                    return;
                }
            }
        }

        // Display board right-click → accept flow
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var block = event.getClickedBlock();
        if (block == null) return;
        Board board = boardManager.findBoard(block.getLocation());
        if (board == null || !board.isDisplay()) return;
        event.setCancelled(true);
        handleDisplayClick(event.getPlayer(), board);
    }

    private void handleDisplayClick(Player player, Board board) {
        var quest = displayManager.getQuestForBoard(board.id());
        if (quest == null) {
            player.sendMessage("§7此告示牌当前没有轮播到委托，请稍后再试。");
            return;
        }
        final var lookupId = quest.id();
        var current = dataManager.loadAll().stream()
                .filter(q -> q.id().equals(lookupId)).findFirst().orElse(null);
        if (current == null || current.status() != QuestStatus.OPEN) {
            player.sendMessage("§7此委托已被接取或取消。");
            return;
        }
        quest = current;

        UUID playerId = player.getUniqueId();
        PendingAccept pending = pendingAccepts.get(playerId);
        if (pending != null && pending.questId().equals(quest.id()) && System.currentTimeMillis() < pending.expireTime()) {
            pendingAccepts.remove(playerId);
            accept(player, quest);
            return;
        }

        String type = quest.isTownQuest() ? "§b[城邦]" : "§a[个人]";
        player.sendMessage("§6======== 委托信息 ========");
        player.sendMessage(type + " §6" + quest.title() + " §7- " + quest.publisherName());
        player.sendMessage("§7需求: §f" + quest.description());
        player.sendMessage("§7报酬: §e$" + String.format("%.0f", quest.reward()));
        player.sendMessage("§7押金: §e$" + String.format("%.0f", quest.deposit()));
        player.sendMessage(" ");
        player.sendMessage("§e⚡ 请在 §65秒内 §e再次右键告示牌确认接取！");
        pendingAccepts.put(playerId, new PendingAccept(quest.id(), System.currentTimeMillis() + 5000));
    }

    private void accept(Player player, com.xinantown.quest.model.Quest quest) {
        if (player.getInventory().getItemInMainHand().getType() != Material.AIR) {
            player.sendMessage("§c请空出主手再接取委托！"); return;
        }
        if (quest.publisherId().equals(player.getUniqueId())) { player.sendMessage("§c不能接取自己的委托。"); return; }
        if (quest.isTownQuest()) {
            var town = com.palmergames.bukkit.towny.TownyAPI.getInstance().getTown(player);
            if (town == null) { player.sendMessage("§c城邦委托只能由城邦接取！"); return; }
            if (plugin.getViolationManager().isBanned(town.getName())) {
                long h = (plugin.getViolationManager().getBanEnd(town.getName()) - System.currentTimeMillis()) / 3600000;
                player.sendMessage("§c城邦处于违约状态，剩余 " + h + " 小时。"); return; }
            if (!town.hasMayor() || !town.getMayor().getUUID().equals(player.getUniqueId())) {
                player.sendMessage("§c只有市长才能代表城邦接取委托！"); return; }
        }
        if (econ == null) { player.sendMessage("§c经济系统未就绪。"); return; }
        double deposit = quest.deposit();
        if (!econ.has(player, deposit)) { player.sendMessage("§c余额不足！需要 $" + String.format("%.0f", deposit)); return; }
        econ.withdrawPlayer(player, deposit);

        long newDeadline = System.currentTimeMillis() + 86400000L * plugin.getConfig().getInt("complete-deadline-days", 7);
        var accepted = quest.accept(player.getUniqueId(), player.getName(), quest.isTownQuest(), newDeadline);
        var all = new ArrayList<>(dataManager.loadAll());
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(quest.id())) { all.set(i, accepted); break; }
        }
        dataManager.saveAll(all);

        QuestScroll scroll = new QuestScroll(plugin);
        player.getInventory().setItemInMainHand(scroll.createScroll(quest.id(), quest.title(), quest.description()));

        player.sendMessage("§a已接取委托 §6" + quest.title() + "§a！");
        player.sendMessage("§e手持委托卷右键打开仓库。");
        displayManager.refreshNow();
    }
}
