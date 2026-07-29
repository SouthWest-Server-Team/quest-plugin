package com.xinantown.quest;

import com.xinantown.quest.model.Board;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles right-click interactions with quest bulletin boards.
 * Center boards show a clickable chat menu.
 * Display boards show quest info + accept flow.
 */
public class BoardClickListener implements Listener {

    private final BoardManager boardManager;
    private final QuestDataManager dataManager;
    private final QuestPlugin plugin;

    // Pending accept confirmations
    private final Map<UUID, PendingAccept> pendingAccepts = new HashMap<>();
    // Display rotation: groupId → current index
    private final Map<Integer, Integer> rotationIndex = new HashMap<>();
    private int rotationSeconds = 30;
    private BukkitTask displayTask;

    private record PendingAccept(UUID questId, long expireTime) {}

    public BoardClickListener(QuestPlugin plugin, BoardManager boardManager,
                               QuestDataManager dataManager) {
        this.plugin = plugin;
        this.boardManager = boardManager;
        this.dataManager = dataManager;
        this.rotationSeconds = plugin.getConfig().getInt("board-rotation-seconds", 30);
    }

    public void start() {
        displayTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            var quests = dataManager.loadAll().stream()
                    .filter(q -> q.status() == com.xinantown.quest.model.QuestStatus.OPEN)
                    .toList();
            rotateDisplays(quests);
        }, 40L, 40L); // every 2s refresh signs
    }

    public void stop() {
        if (displayTask != null) displayTask.cancel();
    }

    // ==================== Display rotation ====================

    private void rotateDisplays(java.util.List<com.xinantown.quest.model.Quest> quests) {
        if (quests.isEmpty()) return;

        long seconds = System.currentTimeMillis() / 1000;

        for (Board board : boardManager.getDisplayBoards()) {
            int groupId = board.groupId();
            java.util.List<Board> groupBoards = boardManager.getGroupBoards(groupId);
            if (groupBoards.isEmpty()) groupBoards = java.util.List.of(board);

            int index = rotationIndex.getOrDefault(groupId, 0);
            int questIndex = (index + groupBoards.indexOf(board)) % quests.size();
            var quest = quests.get(questIndex);

            // Update sign text
            updateSign(board, quest);

            // Advance rotation index every rotationSeconds
            if (board.equals(groupBoards.get(0))
                    && seconds % rotationSeconds == 0) {
                rotationIndex.put(groupId, (index + 1) % quests.size());
            }
        }
    }

    private void updateSign(Board board, com.xinantown.quest.model.Quest quest) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(board.world());
        if (world == null) return;
        Block block = world.getBlockAt(board.x(), board.y(), board.z());
        if (!(block.getState() instanceof Sign sign)) return;

        String type = quest.isTownQuest() ? "§b[城邦]" : "§a[个人]";
        sign.setLine(0, type);
        sign.setLine(1, "§6" + truncateSign(quest.title(), 15));
        sign.setLine(2, "§7报酬: §e$" + String.format("%.0f", quest.reward()));
        sign.setLine(3, "§7右键接取");
        sign.update();
    }

    private String truncateSign(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Scroll right-click → open warehouse
        QuestScroll scroll = new QuestScroll(plugin);
        if (event.getItem() != null && scroll.isScroll(event.getItem())
                && (event.getAction() == Action.RIGHT_CLICK_AIR
                    || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            UUID questId = scroll.getQuestId(event.getItem());
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

        // Board right-click
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        Board board = boardManager.findBoard(block.getLocation());
        if (board == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (board.isCenter()) {
            showCenterMenu(player);
        } else if (board.isDisplay()) {
            showDisplayInfo(player, board);
        }
    }

    // ==================== Center board menu ====================

    private void showCenterMenu(Player player) {
        player.sendMessage("§6======== 委托栏 ========");
        player.sendMessage("§7欢迎使用委托系统！请点击下方按钮操作：");
        player.sendMessage(" ");

        TextComponent publishBtn = new TextComponent("§a§l[发布委托]");
        publishBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/quest create "));
        publishBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§7点击后输入 §e/quest create §7补全参数\n§7格式: <标题> <物品:数量,...> <报酬> [押金]\n§7示例: /quest create 钻石委托 DIAMOND:64 500").create()));
        player.spigot().sendMessage(publishBtn);
        player.sendMessage("  §7→ 创建新的委托（个人/城邦）。点击后补全参数即可。");

        player.spigot().sendMessage(new TextComponent(" "));

        TextComponent myBtn = new TextComponent("§e§l[我的委托]");
        myBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest my"));
        myBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§7查看自己发布的所有委托及状态").create()));
        player.spigot().sendMessage(myBtn);
        player.sendMessage("  §7→ 查看自己发布的委托（含进行中、已完成等状态）。");

        player.spigot().sendMessage(new TextComponent(" "));

        TextComponent cancelBtn = new TextComponent("§c§l[撤回委托]");
        cancelBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest my"));
        cancelBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§7查看我的委托后，在仓库GUI中取消").create()));
        player.spigot().sendMessage(cancelBtn);
        player.sendMessage("  §7→ 在\"我的委托\"中打开仓库，点击\"取消委托\"按钮撤回。");

        player.sendMessage(" ");
        player.sendMessage("§8提示：点击上方§a§l绿色§8或§e§l黄色§8文字按钮操作，§7灰色为说明文字。");
    }

    // ==================== Display board info + accept ====================

    private void showDisplayInfo(Player player, Board board) {
        // Find OPEN quests and show the first one (or rotating)
        var quests = dataManager.loadAll().stream()
                .filter(q -> q.status() == com.xinantown.quest.model.QuestStatus.OPEN)
                .toList();

        if (quests.isEmpty()) {
            player.sendMessage("§7当前没有可接取的委托。");
            return;
        }

        // Simple: show first quest. Rotation handled by display ticker.
        var quest = quests.get(0);

        // Check for pending confirmation
        UUID playerId = player.getUniqueId();
        PendingAccept pending = pendingAccepts.get(playerId);

        if (pending != null && pending.questId().equals(quest.id())
                && System.currentTimeMillis() < pending.expireTime()) {
            // Confirm accept
            pendingAccepts.remove(playerId);
            handleAcceptConfirm(player, quest);
            return;
        }

        // Show quest info + prompt
        player.sendMessage("§6======== 委托信息 ========");
        String type = quest.isTownQuest() ? "§b[城邦]" : "§a[个人]";
        player.sendMessage(type + " §6" + quest.title() + " §7- " + quest.publisherName());
        player.sendMessage("§7需求: " + formatItems(quest.items()));
        player.sendMessage("§7报酬: §e$" + String.format("%.0f", quest.reward()));
        player.sendMessage("§7押金: §e$" + String.format("%.0f", quest.deposit()));
        player.sendMessage(" ");
        player.sendMessage("§e⚡ 请在 §65秒内 §e再次右键告示牌确认接取！");

        pendingAccepts.put(playerId, new PendingAccept(quest.id(),
                System.currentTimeMillis() + 5000));
    }

    private void handleAcceptConfirm(Player player, com.xinantown.quest.model.Quest quest) {
        // Check player has empty main hand
        if (player.getInventory().getItemInMainHand().getType() != Material.AIR) {
            player.sendMessage("§c请空出主手再接取委托！");
            return;
        }

        // Check not own quest
        if (quest.publisherId().equals(player.getUniqueId())) {
            player.sendMessage("§c不能接取自己的委托。");
            return;
        }

        // Town quest: only mayor
        if (quest.isTownQuest()) {
            var town = com.palmergames.bukkit.towny.TownyAPI.getInstance().getTown(player);
            if (town == null) { player.sendMessage("§c城邦委托只能由城邦接取！"); return; }
            if (plugin.getViolationManager().isBanned(town.getName())) {
                long hours = (plugin.getViolationManager().getBanEnd(town.getName()) - System.currentTimeMillis()) / 3600000;
                player.sendMessage("§c城邦处于违约状态，剩余 " + hours + " 小时。");
                return;
            }
            if (!town.hasMayor() || !town.getMayor().getUUID().equals(player.getUniqueId())) {
                player.sendMessage("§c只有市长才能代表城邦接取委托！"); return;
            }
        }

        // Economy
        var econ = org.bukkit.Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (econ == null) { player.sendMessage("§c经济系统未就绪。"); return; }
        double deposit = quest.deposit();
        if (!econ.getProvider().has(player, deposit)) {
            player.sendMessage("§c余额不足！需要支付押金 $" + String.format("%.0f", deposit)); return;
        }
        econ.getProvider().withdrawPlayer(player, deposit);

        // Accept
        long newDeadline = System.currentTimeMillis() + 86400000L * plugin.getConfig().getInt("complete-deadline-days", 7);
        var accepted = quest.accept(player.getUniqueId(), player.getName(),
                quest.isTownQuest(), newDeadline);

        // Save
        java.util.List<com.xinantown.quest.model.Quest> all = new java.util.ArrayList<>(dataManager.loadAll());
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(quest.id())) { all.set(i, accepted); break; }
        }
        dataManager.saveAll(all);

        // Give scroll
        QuestScroll scroll = new QuestScroll(plugin);
        player.getInventory().setItemInMainHand(scroll.createScroll(quest.id(), quest.title()));

        player.sendMessage("§a已接取委托 §6" + quest.title() + "§a！押金: $" + String.format("%.0f", deposit));
        player.sendMessage("§e手持委托卷右键打开仓库。");
    }

    private String formatItems(java.util.List<com.xinantown.quest.model.QuestItem> items) {
        return items.stream()
                .map(i -> i.amount() + "x" + i.material())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
