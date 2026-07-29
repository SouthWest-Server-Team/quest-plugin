package com.xinantown.quest;

import com.xinantown.quest.model.Board;
import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import net.milkbowl.vault.economy.Economy;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles right-click interactions with quest bulletin boards.
 * Center boards show a clickable chat menu.
 * Display boards show quest info + accept flow.
 */
public class BoardClickListener implements Listener {

    private final BoardManager boardManager;
    private final QuestDataManager dataManager;
    private final QuestPlugin plugin;
    private Economy econ;

    // Pending accept confirmations
    private final Map<UUID, PendingAccept> pendingAccepts = new HashMap<>();
    // Display rotation
    private final Map<Integer, Integer> rotationIndex = new HashMap<>();
    private int rotationSeconds = 30;
    private BukkitTask displayTask;
    // Chat-guided creation state
    private final Map<UUID, CreationState> creationStates = new HashMap<>();

    private record PendingAccept(UUID questId, long expireTime) {}

    /** Tracks the state of chat-guided quest creation. */
    private static class CreationState {
        String type;       // "material" or "build"
        String title;      // auto for material, user for build
        String desc;       // material description or build description
        int amount;        // only for material
        double reward;
        double deposit;
        int step;          // 0=not started, 1-4=waiting input, 5=await confirm
        boolean isTown;
    }

    public BoardClickListener(QuestPlugin plugin, BoardManager boardManager,
                               QuestDataManager dataManager) {
        this.plugin = plugin;
        this.boardManager = boardManager;
        this.dataManager = dataManager;
        this.rotationSeconds = plugin.getConfig().getInt("board-rotation-seconds", 30);
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) this.econ = rsp.getProvider();
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
        publishBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest publish"));
        publishBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§7点击开始聊天引导发布流程").create()));
        player.spigot().sendMessage(publishBtn);
        player.sendMessage("  §7→ 聊天栏逐步引导发布（材料/建筑委托）。");
        player.sendMessage(" ");

        TextComponent myBtn = new TextComponent("§e§l[我的委托]");
        myBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest my"));
        player.spigot().sendMessage(myBtn);
        player.sendMessage("  §7→ 查看自己发布的委托及状态。");

        player.sendMessage(" ");
        player.sendMessage("§8提示：点击上方§a§l绿色§8或§e§l黄色§8文字按钮操作。");
    }

    // ==================== Guided creation ====================

    /** Start guided creation: show type selector. Called by /quest publish */
    public void startPublish(Player player) {
        player.sendMessage("§6=== 选择委托类型 ===");

        TextComponent matBtn = new TextComponent("§a§l[材料委托]");
        matBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest create mat"));
        matBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§7材料: 橡木 × 64").create()));
        player.spigot().sendMessage(matBtn);

        TextComponent buildBtn = new TextComponent("§b§l[建筑委托]");
        buildBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest create build"));
        buildBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§7建筑: 城堡建造").create()));
        player.spigot().sendMessage(buildBtn);
    }

    /** Start material creation flow. */
    public void startMaterialCreation(Player player) {
        CreationState s = new CreationState();
        s.type = "material";
        s.step = 1;
        creationStates.put(player.getUniqueId(), s);
        player.sendMessage("§e[第1步] §7请输入材料描述（例: §f橡木原木§7）：");
    }

    /** Start build creation flow. */
    public void startBuildCreation(Player player) {
        CreationState s = new CreationState();
        s.type = "build";
        s.step = 1;
        creationStates.put(player.getUniqueId(), s);
        player.sendMessage("§e[第1步] §7请输入委托标题（例: §f城堡建造§7）：");
    }

    /** Handle each step of chat input for creation. */
    public void handleCreationInput(Player player, String input) {
        UUID id = player.getUniqueId();
        CreationState s = creationStates.get(id);
        if (s == null) return;

        if (s.type.equals("material")) {
            handleMaterialInput(player, s, input);
        } else {
            handleBuildInput(player, s, input);
        }
    }

    private void handleMaterialInput(Player player, CreationState s, String input) {
        switch (s.step) {
            case 1 -> {
                s.desc = input;
                s.step = 2;
                player.sendMessage("§e[第2步] §7请输入需求数量（例: §f64§7）：");
            }
            case 2 -> {
                try { s.amount = Integer.parseInt(input); } catch (NumberFormatException e) {
                    player.sendMessage("§c请输入有效数字。"); return;
                }
                s.step = 3;
                player.sendMessage("§e[第3步] §7请输入报酬金额（例: §f500§7）：");
            }
            case 3 -> {
                try { s.reward = Double.parseDouble(input); } catch (NumberFormatException e) {
                    player.sendMessage("§c请输入有效数字。"); return;
                }
                s.step = 4;
                player.sendMessage("§e[第4步] §7请输入押金（回车默认=报酬 §f$" + String.format("%.0f", s.reward) + "§7）：");
            }
            case 4 -> {
                if (input.isEmpty()) { s.deposit = s.reward; }
                else { try { s.deposit = Double.parseDouble(input); } catch (NumberFormatException e) {
                    player.sendMessage("§c请输入有效数字或回车跳过。"); return; } }
                s.title = s.desc + " × " + s.amount;
                s.step = 5;
                showConfirm(player, s);
            }
        }
    }

    private void handleBuildInput(Player player, CreationState s, String input) {
        switch (s.step) {
            case 1 -> {
                s.title = input;
                s.step = 2;
                player.sendMessage("§e[第2步] §7请输入建筑描述（例: §f建造一座城堡§7）：");
            }
            case 2 -> {
                s.desc = input;
                s.step = 3;
                player.sendMessage("§e[第3步] §7请输入报酬金额（例: §f500§7）：");
            }
            case 3 -> {
                try { s.reward = Double.parseDouble(input); } catch (NumberFormatException e) {
                    player.sendMessage("§c请输入有效数字。"); return;
                }
                s.step = 4;
                player.sendMessage("§e[第4步] §7请输入押金（回车默认=报酬 §f$" + String.format("%.0f", s.reward) + "§7）：");
            }
            case 4 -> {
                if (input.isEmpty()) { s.deposit = s.reward; }
                else { try { s.deposit = Double.parseDouble(input); } catch (NumberFormatException e) {
                    player.sendMessage("§c请输入有效数字或回车跳过。"); return; } }
                s.step = 5;
                showConfirm(player, s);
            }
        }
    }

    private void showConfirm(Player player, CreationState s) {
        String typeLabel = s.type.equals("material") ? "§a材料委托" : "§b建筑委托";
        player.sendMessage("§6======== 委托确认 ========");
        player.sendMessage("§7类型: " + typeLabel);
        player.sendMessage("§7标题: §f" + s.title);
        player.sendMessage("§7描述: §f" + s.desc);
        player.sendMessage("§7报酬: §e$" + String.format("%.0f", s.reward));
        player.sendMessage("§7押金: §e$" + String.format("%.0f", s.deposit));
        player.sendMessage(" ");

        TextComponent confirmBtn = new TextComponent("§a§l[确认发布]");
        confirmBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest create confirm"));
        player.spigot().sendMessage(confirmBtn);

        TextComponent cancelBtn = new TextComponent("§c§l[取消]");
        cancelBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest create cancel"));
        player.spigot().sendMessage(cancelBtn);
    }

    /** Final confirm + create quest. */
    public void confirmPublish(Player player) {
        CreationState s = creationStates.get(player.getUniqueId());
        if (s == null || s.step != 5) return;

        if (econ == null) { player.sendMessage("§c经济系统未就绪。"); creationStates.remove(player.getUniqueId()); return; }
        double total = s.deposit + s.reward;
        if (!econ.has(player, total)) {
            player.sendMessage("§c余额不足！需要 $" + String.format("%.0f", total)); creationStates.remove(player.getUniqueId()); return;
        }
        econ.withdrawPlayer(player, total);

        long now = System.currentTimeMillis();
        int acceptDays = plugin.getConfig().getInt("accept-deadline-days", 7);
        int completeDays = plugin.getConfig().getInt("complete-deadline-days", 7);
        Quest quest = new Quest(UUID.randomUUID(), s.title, player.getUniqueId(), player.getName(),
                s.isTown, s.type, s.desc, s.reward, s.deposit,
                now + 86400000L * acceptDays, now + 86400000L * completeDays,
                QuestStatus.OPEN, null, null, false, 0);

        List<Quest> all = new ArrayList<>(dataManager.loadAll());
        all.add(quest);
        dataManager.saveAll(all);
        creationStates.remove(player.getUniqueId());

        player.sendMessage("§a委托 §6" + s.title + " §a已创建！押金+报酬: $" + String.format("%.0f", total));
    }

    public void cancelPublish(Player player) {
        creationStates.remove(player.getUniqueId());
        player.sendMessage("§e已取消发布。");
    }

    public boolean hasCreationState(Player player) {
        return creationStates.containsKey(player.getUniqueId());
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
        player.sendMessage("§7需求: §f" + quest.description());
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
        if (econ == null) { player.sendMessage("§c经济系统未就绪。"); return; }
        double deposit = quest.deposit();
        if (!econ.has(player, deposit)) {
            player.sendMessage("§c余额不足！需要支付押金 $" + String.format("%.0f", deposit)); return;
        }
        econ.withdrawPlayer(player, deposit);

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
        player.getInventory().setItemInMainHand(
                scroll.createScroll(quest.id(), quest.title(), quest.description()));

        player.sendMessage("§a已接取委托 §6" + quest.title() + "§a！押金: $" + String.format("%.0f", deposit));
        player.sendMessage("§e手持委托卷右键打开仓库。");
    }


    @EventHandler
    public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!creationStates.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        // Run on main thread
        Bukkit.getScheduler().runTask(plugin, () -> handleCreationInput(player, event.getMessage()));
    }
}
