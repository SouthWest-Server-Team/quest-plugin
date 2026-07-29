package com.xinantown.quest;

import com.xinantown.quest.model.QuestStatus;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.*;

/**
 * Handles center board chat menu and chat-guided quest creation flow.
 */
public class BoardMenuHandler implements Listener {

    private final QuestPlugin plugin;
    private final BoardManager boardManager;
    private final QuestDataManager dataManager;
    private Economy econ;
    private final Map<UUID, CreationState> creationStates = new HashMap<>();

    static class CreationState {
        String type, title, desc;
        int amount;
        double reward, deposit;
        int step;
        boolean isTown;
    }

    public BoardMenuHandler(QuestPlugin plugin, BoardManager boardManager, QuestDataManager dataManager) {
        this.plugin = plugin;
        this.boardManager = boardManager;
        this.dataManager = dataManager;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) this.econ = rsp.getProvider();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var block = event.getClickedBlock();
        if (block == null) return;
        var board = boardManager.findBoard(block.getLocation());
        if (board == null || !board.isCenter()) return;
        event.setCancelled(true);
        showCenterMenu(event.getPlayer());
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!creationStates.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> handleCreationInput(player, event.getMessage()));
    }

    // ==================== Center menu ====================

    void showCenterMenu(Player player) {
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

    public void startPublish(Player player) {
        player.sendMessage("§6=== 选择委托类型 ===");
        for (var entry : Map.of(
                "§a§l[材料委托]", "/quest create mat",
                "§b§l[建筑委托]", "/quest create build",
                "§d§l[城邦材料委托]", "/quest create tmat",
                "§5§l[城邦建筑委托]", "/quest create tbuild"
        ).entrySet()) {
            TextComponent btn = new TextComponent(entry.getKey());
            btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, entry.getValue()));
            btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("§7点击选择").create()));
            player.spigot().sendMessage(btn);
        }
    }

    public void startCreation(Player player, String type, boolean isTown) {
        CreationState s = new CreationState();
        s.type = type;
        s.isTown = isTown;
        s.step = 1;
        creationStates.put(player.getUniqueId(), s);
        player.sendMessage(type.equals("material")
                ? "§e[第1步] §7请输入材料描述（例: §f橡木原木§7）："
                : "§e[第1步] §7请输入委托标题（例: §f城堡建造§7）：");
    }

    private boolean checkMayor(Player player) {
        var town = com.palmergames.bukkit.towny.TownyAPI.getInstance().getTown(player);
        if (town == null) { player.sendMessage("§c你不属于任何城邦！"); return false; }
        if (!town.hasMayor() || !town.getMayor().getUUID().equals(player.getUniqueId())) {
            player.sendMessage("§c只有市长才能发布城邦委托！"); return false; }
        return true;
    }

    public void startMaterialCreation(Player player) { startCreation(player, "material", false); }
    public void startBuildCreation(Player player) { startCreation(player, "build", false); }

    public void startTownMaterialCreation(Player player) {
        if (!checkMayor(player)) return;
        startCreation(player, "material", true);
    }
    public void startTownBuildCreation(Player player) {
        if (!checkMayor(player)) return;
        startCreation(player, "build", true);
    }

    void handleCreationInput(Player player, String input) {
        CreationState s = creationStates.get(player.getUniqueId());
        if (s == null) return;
        if (s.type.equals("material")) handleMaterialInput(player, s, input);
        else handleBuildInput(player, s, input);
    }

    private void handleMaterialInput(Player player, CreationState s, String input) {
        switch (s.step) {
            case 1 -> { s.desc = input; s.step = 2; player.sendMessage("§e[第2步] §7请输入需求数量:"); }
            case 2 -> { try { s.amount = Integer.parseInt(input); } catch (NumberFormatException e) { player.sendMessage("§c请输入有效数字。"); return; } s.step = 3; player.sendMessage("§e[第3步] §7请输入报酬金额:"); }
            case 3 -> { try { s.reward = Double.parseDouble(input); } catch (NumberFormatException e) { player.sendMessage("§c请输入有效数字。"); return; } s.step = 4; player.sendMessage("§e[第4步] §7请输入押金（回车默认=报酬 §f$" + String.format("%.0f", s.reward) + "§7）:"); }
            case 4 -> { if (input.isEmpty()) s.deposit = s.reward; else { try { s.deposit = Double.parseDouble(input); } catch (NumberFormatException e) { player.sendMessage("§c请输入有效数字或回车跳过。"); return; } } s.title = s.desc + " × " + s.amount; s.step = 5; showConfirm(player, s); }
        }
    }

    private void handleBuildInput(Player player, CreationState s, String input) {
        switch (s.step) {
            case 1 -> { s.title = input; s.step = 2; player.sendMessage("§e[第2步] §7请输入建筑描述:"); }
            case 2 -> { s.desc = input; s.step = 3; player.sendMessage("§e[第3步] §7请输入报酬金额:"); }
            case 3 -> { try { s.reward = Double.parseDouble(input); } catch (NumberFormatException e) { player.sendMessage("§c请输入有效数字。"); return; } s.step = 4; player.sendMessage("§e[第4步] §7请输入押金（回车默认=报酬 §f$" + String.format("%.0f", s.reward) + "§7）:"); }
            case 4 -> { if (input.isEmpty()) s.deposit = s.reward; else { try { s.deposit = Double.parseDouble(input); } catch (NumberFormatException e) { player.sendMessage("§c请输入有效数字或回车跳过。"); return; } } s.step = 5; showConfirm(player, s); }
        }
    }

    private void showConfirm(Player player, CreationState s) {
        player.sendMessage("§6======== 委托确认 ========");
        player.sendMessage("§7类型: §f" + (s.type.equals("material") ? "材料委托" : "建筑委托"));
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

    public void confirmPublish(Player player) {
        CreationState s = creationStates.get(player.getUniqueId());
        if (s == null || s.step != 5) return;
        if (econ == null) { player.sendMessage("§c经济系统未就绪。"); creationStates.remove(player.getUniqueId()); return; }
        double total = s.deposit + s.reward;
        if (!econ.has(player, total)) { player.sendMessage("§c余额不足！需要 $" + String.format("%.0f", total)); creationStates.remove(player.getUniqueId()); return; }
        econ.withdrawPlayer(player, total);
        long now = System.currentTimeMillis();
        int acceptDays = plugin.getConfig().getInt("accept-deadline-days", 7);
        int completeDays = plugin.getConfig().getInt("complete-deadline-days", 7);
        var quest = new com.xinantown.quest.model.Quest(UUID.randomUUID(), s.title, player.getUniqueId(), player.getName(),
                s.isTown, s.type, s.desc, s.reward, s.deposit,
                now + 86400000L * acceptDays, now + 86400000L * completeDays,
                QuestStatus.OPEN, null, null, false, 0);
        List<com.xinantown.quest.model.Quest> all = new ArrayList<>(dataManager.loadAll());
        all.add(quest);
        dataManager.saveAll(all);
        creationStates.remove(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new com.xinantown.quest.event.QuestCreatedEvent(quest));
        player.sendMessage("§a委托 §6" + s.title + " §a已创建！");
    }

    public void cancelPublish(Player player) {
        creationStates.remove(player.getUniqueId());
        player.sendMessage("§e已取消发布。");
    }

    public boolean hasCreationState(Player player) {
        return creationStates.containsKey(player.getUniqueId());
    }
}
