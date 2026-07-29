package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.*;
import java.util.stream.Collectors;

public class QuestGuiManager implements Listener {

    private final QuestPlugin plugin;
    private final QuestDataManager dataManager;
    private final Map<UUID, Quest> openGuis = new HashMap<>();
    private final Map<UUID, Boolean> approveConfirm = new HashMap<>(); // confirm state
    private final Map<UUID, Quest> pendingReject = new HashMap<>(); // awaiting reason
    private Economy econ;

    public QuestGuiManager(QuestPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) econ = rsp.getProvider();
    }

    public void openWarehouse(Player player, Quest quest) {
        // Guard: completed or cancelled quests have no warehouse
        if (quest.status() == QuestStatus.COMPLETED || quest.status() == QuestStatus.CANCELLED) {
            player.sendMessage("§7该委托已结束，仓库已关闭。");
            return;
        }
        int size = 54;
        Inventory inv = Bukkit.createInventory(null, size, "§8委托仓库 - " + truncate(quest.title(), 20));

        // Load saved warehouse items
        Map<Integer, ItemStack> saved = plugin.getWarehouseManager().load(quest.id());
        for (var e : saved.entrySet()) {
            if (e.getKey() < size - 9) inv.setItem(e.getKey(), e.getValue());
        }

        // Add buttons on last row
        boolean isAcceptor = quest.acceptorId() != null && quest.acceptorId().equals(player.getUniqueId());
        boolean isPublisher = quest.publisherId().equals(player.getUniqueId());

        if (isAcceptor && quest.status() == QuestStatus.ACCEPTED) {
            inv.setItem(size - 5, createButton(Material.LIME_STAINED_GLASS_PANE, "§a提交委托"));
            inv.setItem(size - 4, createButton(Material.YELLOW_STAINED_GLASS_PANE, "§e暂存"));
            inv.setItem(size - 3, createButton(Material.RED_STAINED_GLASS_PANE, "§c取消委托"));
        }
        if (isPublisher && quest.status() == QuestStatus.SUBMITTED) {
            inv.setItem(size - 6, createButton(Material.GREEN_STAINED_GLASS_PANE, "§a同意"));
            inv.setItem(size - 5, createButton(Material.RED_STAINED_GLASS_PANE, "§c驳回 (" + quest.rejectCount() + "/" + plugin.getConfig().getInt("max-rejects", 5) + ")"));
            if (quest.rejectCount() >= plugin.getConfig().getInt("max-rejects", 5) - 1) {
                inv.setItem(size - 4, createButton(Material.BARRIER, "§4终止委托"));
            }
        }
        if (isPublisher) {
            inv.setItem(size - 1, createButton(Material.BARRIER, "§c取消委托"));
        }

        openGuis.put(player.getUniqueId(), quest);
        player.openInventory(inv);
    }

    /**
     * 玩家加入时清理已完成的委托卷（处理离线时未销毁的情况）。
     */
    public void cleanupScrollsOnJoin(Player player) {
        var all = dataManager.loadAll();
        for (Quest q : all) {
            if (q.status() == QuestStatus.COMPLETED || q.status() == QuestStatus.CANCELLED) {
                if (q.acceptorId() != null && q.acceptorId().equals(player.getUniqueId())) {
                    QuestScroll.removeFromInventory(player, q.id(), plugin);
                }
                if (q.publisherId().equals(player.getUniqueId())) {
                    QuestScroll.removeFromInventory(player, q.id(), plugin);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        cleanupScrollsOnJoin(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Quest quest = openGuis.get(player.getUniqueId());
        if (quest == null) return;
        // Guard: completed/cancelled quests have no interactive warehouse
        if (quest.status() == QuestStatus.COMPLETED || quest.status() == QuestStatus.CANCELLED) {
            openGuis.remove(player.getUniqueId());
            player.closeInventory();
            return;
        }
        if (!event.getView().getTitle().contains("委托仓库")) return;

        int slot = event.getSlot();
        int size = event.getInventory().getSize();
        boolean isAcceptor = quest.acceptorId() != null && quest.acceptorId().equals(player.getUniqueId());
        boolean isPublisher = quest.publisherId().equals(player.getUniqueId());

        // Button clicks
        String btn = getButtonLabel(event.getCurrentItem());
        if (btn != null) {
            event.setCancelled(true);
            if (btn.equals("§a提交委托") && isAcceptor) handleSubmit(player, quest, event.getInventory(), size);
            else if (btn.equals("§e暂存") && isAcceptor) handleSave(player, quest, event.getInventory(), size);
            else if (btn.equals("§a同意") && isPublisher) {
                if (approveConfirm.getOrDefault(player.getUniqueId(), false)) {
                    approveConfirm.remove(player.getUniqueId());
                    handleApprove(player, quest);
                } else {
                    approveConfirm.put(player.getUniqueId(), true);
                    event.getInventory().setItem(slot, createButton(Material.GREEN_STAINED_GLASS_PANE, "§a确认同意"));
                    int revertSlot = slot + 1;
                    if (revertSlot < size) {
                        event.getInventory().setItem(revertSlot, createButton(Material.GRAY_STAINED_GLASS_PANE, "§7返回"));
                    }
                }
            }
            else if (btn.equals("§a确认同意") && isPublisher) {
                approveConfirm.remove(player.getUniqueId());
                handleApprove(player, quest);
            }
            else if (btn.equals("§7返回") && isPublisher) {
                approveConfirm.remove(player.getUniqueId());
                player.closeInventory();
            }
            else if (btn.startsWith("§c驳回") && isPublisher) handleReject(player, quest);
            else if (btn.equals("§4终止委托") && isPublisher) handleTerminate(player, quest);
            else if (btn.equals("§c取消委托") && (isPublisher || isAcceptor)) handleCancel(player, quest);
            return;
        }

        // Acceptor can modify inventory; publisher is read-only
        if (!isAcceptor) { event.setCancelled(true); return; }
        // Allow clicks in main area only (not button row)
        if (slot >= size - 9) { event.setCancelled(true); return; }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Quest quest = openGuis.remove(player.getUniqueId());
        if (quest == null) return;
        // Auto-save on close
        Map<Integer, ItemStack> items = new HashMap<>();
        for (int i = 0; i < event.getInventory().getSize() - 9; i++) {
            ItemStack item = event.getInventory().getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                items.put(i, item);
            }
        }
        plugin.getWarehouseManager().save(quest.id(), items);
    }

    private void handleSubmit(Player player, Quest quest, Inventory inv, int size) {
        Map<Integer, ItemStack> items = new HashMap<>();
        for (int i = 0; i < size - 9; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) items.put(i, item);
        }
        plugin.getWarehouseManager().save(quest.id(), items);
        updateQuest(player, quest.submit());
        player.closeInventory();
        player.sendMessage("§a委托已提交，等待发布者确认！");
        Player pub = Bukkit.getPlayer(quest.publisherId());
        if (pub != null) {
            String shortId = quest.id().toString().substring(0, 8);
            pub.sendMessage("§a[委托] §6" + quest.title() + " §a已提交，");
            TextComponent click = new TextComponent("§6§l[点击这里打开仓库]");
            click.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest warehouse " + shortId));
            click.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("§7打开 " + quest.title() + " 的仓库").create()));
            pub.spigot().sendMessage(click);
        }
    }

    private void handleSave(Player player, Quest quest, Inventory inv, int size) {
        Map<Integer, ItemStack> items = new HashMap<>();
        for (int i = 0; i < size - 9; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) items.put(i, item);
        }
        plugin.getWarehouseManager().save(quest.id(), items);
        player.sendMessage("§e仓库已暂存。");
    }

    private void handleApprove(Player player, Quest quest) {
        // 需求3: 先移除接收方的委托卷
        Player acceptor = quest.acceptorId() != null ? Bukkit.getPlayer(quest.acceptorId()) : null;
        if (acceptor != null && acceptor.isOnline()) {
            QuestScroll.removeFromInventory(acceptor, quest.id(), plugin);
        }

        // Transfer warehouse items to publisher
        Map<Integer, ItemStack> items = plugin.getWarehouseManager().load(quest.id());
        if (!items.isEmpty() && player.isOnline()) {
            for (ItemStack item : items.values()) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                for (ItemStack overflow : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow);
                }
            }
            plugin.getWarehouseManager().delete(quest.id());
        }

        if (econ != null) {
            econ.depositPlayer(player, quest.deposit());
            econ.depositPlayer(Bukkit.getOfflinePlayer(quest.acceptorId()), quest.deposit() + quest.reward());
        }
        updateQuest(player, quest.complete());
        Bukkit.getPluginManager().callEvent(new com.xinantown.quest.event.QuestCompletedEvent(quest));
        QuestScroll.removeFromInventory(player, quest.id(), plugin);
        player.closeInventory();
        player.sendMessage("§a委托已完成！物品已发放到你的背包。");
        if (acceptor != null) acceptor.sendMessage("§a[委托] §6" + quest.title() + " §a已完成！报酬+押金已到账。");
    }

    private void handleReject(Player player, Quest quest) {
        pendingReject.put(player.getUniqueId(), quest);
        player.closeInventory();
        player.sendMessage("§e请在聊天栏输入驳回理由（30秒内有效）：");
    }

    private void executeReject(Player player, Quest quest, String reason) {
        pendingReject.remove(player.getUniqueId());
        int max = plugin.getConfig().getInt("max-rejects", 5);
        updateQuest(player, quest.reject(max));

        if (quest.rejectCount() + 1 >= max) {
            player.sendMessage("§c委托已因多次驳回而终止。");
        } else {
            player.sendMessage("§e委托已驳回（第 " + (quest.rejectCount() + 1) + " 次）。");
        }
        Player acc = Bukkit.getPlayer(quest.acceptorId());
        if (acc != null) {
            acc.sendMessage("§c[委托] §6" + quest.title() + " §c第 " + (quest.rejectCount() + 1) + " 次被驳回: §7" + reason);
        }
    }

    @EventHandler
    public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Quest quest = pendingReject.get(player.getUniqueId());
        if (quest == null) return;

        event.setCancelled(true);
        executeReject(player, quest, event.getMessage());
    }

    public boolean hasPendingInput(Player player) {
        return pendingReject.containsKey(player.getUniqueId());
    }

    private void handleTerminate(Player player, Quest quest) {
        if (econ != null) {
            econ.depositPlayer(player, quest.deposit());
            econ.depositPlayer(Bukkit.getOfflinePlayer(quest.acceptorId()), quest.deposit());
        }
        updateQuest(player, quest.cancel());
        Bukkit.getPluginManager().callEvent(new com.xinantown.quest.event.QuestCompletedEvent(quest));
        // 移除双方委托卷
        Player acceptor = quest.acceptorId() != null ? Bukkit.getPlayer(quest.acceptorId()) : null;
        if (acceptor != null && acceptor.isOnline()) {
            QuestScroll.removeFromInventory(acceptor, quest.id(), plugin);
        }
        QuestScroll.removeFromInventory(player, quest.id(), plugin);
        player.closeInventory();
        player.sendMessage("§c委托已终止，双方押金已退还。");
    }

    private void handleCancel(Player player, Quest quest) {
        boolean isPublisher = quest.publisherId().equals(player.getUniqueId());
        if (econ != null) {
            if (quest.status() == QuestStatus.ACCEPTED) {
                if (isPublisher) {
                    double penalty = quest.reward() * plugin.getConfig().getDouble("cancel-penalty-publisher", 0.5);
                    if (!econ.has(player, penalty)) {
                        player.sendMessage("§c余额不足，无法支付取消罚款 $" + String.format("%.0f", penalty));
                        return;
                    }
                    econ.withdrawPlayer(player, penalty);
                    econ.depositPlayer(Bukkit.getOfflinePlayer(quest.acceptorId()), penalty);
                    econ.depositPlayer(player, quest.deposit()); // 退回押金
                    player.sendMessage("§c委托已取消，罚款 $" + String.format("%.0f", penalty) + " 已支付，押金已退还。");
                } else {
                    // Acceptor cancel
                    double penalty = quest.reward() * plugin.getConfig().getDouble("cancel-penalty-acceptor", 3.0);
                    if (!econ.has(player, penalty)) {
                        player.sendMessage("§c余额不足，无法支付取消罚款 $" + String.format("%.0f", penalty));
                        return;
                    }
                    econ.withdrawPlayer(player, penalty);
                    econ.depositPlayer(Bukkit.getOfflinePlayer(quest.publisherId()), penalty);
                    econ.depositPlayer(player, quest.deposit()); // 退回押金
                    player.sendMessage("§c委托已取消，罚款 $" + String.format("%.0f", penalty) + " 已支付，押金已退还。");
                }
            } else {
                // Not yet accepted — just refund deposit
                econ.depositPlayer(player, quest.deposit());
                player.sendMessage("§e委托已撤回，押金已退还。");
            }
        }
        updateQuest(player, quest.cancel());
        Bukkit.getPluginManager().callEvent(new com.xinantown.quest.event.QuestCompletedEvent(quest));
        // 移除双方委托卷
        Player acceptor = quest.acceptorId() != null ? Bukkit.getPlayer(quest.acceptorId()) : null;
        if (acceptor != null && acceptor.isOnline()) {
            QuestScroll.removeFromInventory(acceptor, quest.id(), plugin);
        }
        QuestScroll.removeFromInventory(player, quest.id(), plugin);
        player.closeInventory();
    }

    private void updateQuest(Player player, Quest updated) {
        List<Quest> all = new ArrayList<>(dataManager.loadAll());
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(updated.id())) { all.set(i, updated); break; }
        }
        dataManager.saveAll(all);
        openGuis.put(player.getUniqueId(), updated);
    }

    private ItemStack createButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private String getButtonLabel(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return null;
        String name = item.getItemMeta().getDisplayName();
        return name.startsWith("§") ? name : null;
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
