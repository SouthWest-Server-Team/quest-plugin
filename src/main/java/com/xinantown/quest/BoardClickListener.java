package com.xinantown.quest;

import com.xinantown.quest.model.Board;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

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

    // Pending accept confirmations: player UUID → {questId, expireTime}
    private final Map<UUID, PendingAccept> pendingAccepts = new HashMap<>();

    private record PendingAccept(UUID questId, long expireTime) {}

    public BoardClickListener(BoardManager boardManager, QuestDataManager dataManager) {
        this.boardManager = boardManager;
        this.dataManager = dataManager;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
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

        TextComponent publishBtn = new TextComponent("§a[发布委托]");
        publishBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/quest create "));
        publishBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§7点击填充发布命令").create()));
        player.spigot().sendMessage(publishBtn);

        player.spigot().sendMessage(new TextComponent(" "));

        TextComponent myBtn = new TextComponent("§e[我的委托]");
        myBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest my"));
        myBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§7查看自己发布的委托").create()));
        player.spigot().sendMessage(myBtn);

        player.spigot().sendMessage(new TextComponent(" "));

        TextComponent cancelBtn = new TextComponent("§c[撤回委托]");
        cancelBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest my"));
        cancelBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§7查看并撤回自己的委托").create()));
        player.spigot().sendMessage(cancelBtn);

        player.sendMessage("§8提示：点击上方按钮操作");
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

        // TODO: full accept logic (T05)
        player.sendMessage("§a已确认接取委托 §6" + quest.title() + "§a！");
    }

    private String formatItems(java.util.List<com.xinantown.quest.model.QuestItem> items) {
        return items.stream()
                .map(i -> i.amount() + "x" + i.material())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
