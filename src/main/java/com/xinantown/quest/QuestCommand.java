package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import com.xinantown.quest.model.Board;
import com.xinantown.quest.model.QuestFilter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.*;
import java.util.stream.Collectors;

public class QuestCommand implements CommandExecutor, TabCompleter {

    private final QuestPlugin plugin;
    private final QuestDataManager dataManager;
    private final BoardManager boardManager;
    private Economy econ;

    public QuestCommand(QuestPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
        this.boardManager = plugin.getBoardManager();
        setupEconomy();
    }

    private void setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) econ = rsp.getProvider();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { sendHelp(sender); return true; }
        return switch (args[0].toLowerCase()) {
            case "list" -> handleList(sender);
            case "accept" -> handleAccept(sender, args);
            case "warehouse" -> handleWarehouse(sender, args);
            case "board" -> handleBoard(sender, args);
            case "my" -> handleMy(sender);
            case "publish" -> handlePublish(sender);
            case "create" -> handleCreateSub(sender, args);
            default -> { sendHelp(sender); yield true; }
        };
    }

    // ==================== list ====================

    private boolean handleList(CommandSender sender) {
        List<Quest> all = dataManager.loadAll();
        List<Quest> open = all.stream().filter(q -> q.status() == QuestStatus.OPEN).toList();
        if (open.isEmpty()) { sender.sendMessage("§7当前没有可接取的委托。"); return true; }

        sender.sendMessage("§6=== 可接取委托 (" + open.size() + ") ===");
        for (Quest q : open) {
            String type = q.isTownQuest() ? "§b[城邦]" : "§a[个人]";
            String qType = q.isMaterialQuest() ? "§7[材料]" : "§7[建筑]";
            sender.sendMessage(type + qType + " §6" + q.title() + " §7- " + q.publisherName());
            sender.sendMessage("  §7" + q.description() + " §7报酬: $"
                    + String.format("%.0f", q.reward()));
            sender.sendMessage("  §7ID: §8" + q.id().toString().substring(0, 8));
        }
        return true;
    }

    // ==================== accept ====================

    private boolean handleAccept(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§c玩家专用命令。"); return true; }
        if (args.length < 2) { player.sendMessage("§c用法: /quest accept <委托ID前8位>"); return true; }

        String prefix = args[1].toLowerCase();
        List<Quest> all = new ArrayList<>(dataManager.loadAll());
        Quest found = all.stream()
                .filter(q -> q.status() == QuestStatus.OPEN && q.id().toString().toLowerCase().startsWith(prefix))
                .findFirst().orElse(null);

        if (found == null) { player.sendMessage("§c找不到该委托或已被接取。"); return true; }
        if (found.publisherId().equals(player.getUniqueId())) { player.sendMessage("§c不能接取自己的委托。"); return true; }

        if (found.isTownQuest()) {
            var town = com.palmergames.bukkit.towny.TownyAPI.getInstance().getTown(player);
            if (town == null) { player.sendMessage("§c城邦委托只能由城邦接取！"); return true; }
            if (plugin.getViolationManager().isBanned(town.getName())) {
                long hours = (plugin.getViolationManager().getBanEnd(town.getName()) - System.currentTimeMillis()) / 3600000;
                player.sendMessage("§c城邦处于违约状态，剩余 " + hours + " 小时。");
                return true;
            }
            if (!town.hasMayor() || !town.getMayor().getUUID().equals(player.getUniqueId())) {
                player.sendMessage("§c只有市长才能代表城邦接取委托！"); return true;
            }
        }

        if (econ == null) { player.sendMessage("§c经济系统未就绪。"); return true; }
        double deposit = found.deposit();
        if (!econ.has(player, deposit)) {
            player.sendMessage("§c余额不足！需要支付押金 $" + String.format("%.0f", deposit)); return true;
        }
        econ.withdrawPlayer(player, deposit);

        long newDeadline = System.currentTimeMillis() + 86400000L * plugin.getConfig().getInt("complete-deadline-days", 7);
        Quest accepted = found.accept(player.getUniqueId(), player.getName(),
                found.isTownQuest(), newDeadline);

        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(found.id())) { all.set(i, accepted); break; }
        }
        dataManager.saveAll(all);

        player.sendMessage("§a已接取委托 §6" + found.title() + "§a！押金: $" + String.format("%.0f", deposit));
        Player pub = Bukkit.getPlayer(found.publisherId());
        if (pub != null) pub.sendMessage("§a[委托] §6" + player.getName() + " §a接取了你的委托 §6" + found.title());
        return true;
    }

    // ==================== warehouse ====================

    private boolean handleWarehouse(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§c玩家专用命令。"); return true; }
        if (args.length < 2) { player.sendMessage("§c用法: /quest warehouse <ID前8位>"); return true; }

        String prefix = args[1].toLowerCase();
        List<Quest> all = dataManager.loadAll();
        Quest found = all.stream()
                .filter(q -> q.id().toString().toLowerCase().startsWith(prefix))
                .findFirst().orElse(null);
        if (found == null) { player.sendMessage("§c找不到该委托。"); return true; }

        if (!found.publisherId().equals(player.getUniqueId()) &&
                (found.acceptorId() == null || !found.acceptorId().equals(player.getUniqueId()))) {
            player.sendMessage("§c你不是该委托的参与方。"); return true;
        }

        plugin.getGuiManager().openWarehouse(player, found);
        return true;
    }

    // ==================== my ====================

    private boolean handleMy(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c玩家专用命令。"); return true;
        }
        List<Quest> all = dataManager.loadAll();
        List<Quest> mine = all.stream()
                .filter(q -> q.publisherId().equals(player.getUniqueId()))
                .toList();

        if (mine.isEmpty()) { player.sendMessage("§7你还没有发布过委托。"); return true; }

        player.sendMessage("§6=== 我的委托 (" + mine.size() + ") ===");
        for (Quest q : mine) {
            String status = switch (q.status()) {
                case OPEN -> "§a可接取";
                case ACCEPTED -> "§e进行中";
                case SUBMITTED -> "§b待确认";
                case COMPLETED -> "§7已完成";
                case CANCELLED -> "§c已取消";
            };
            player.sendMessage("§6" + q.title() + " §7- " + status
                    + " §8[" + q.id().toString().substring(0, 8) + "]");
        }
        return true;
    }

    // ==================== board ====================

    private boolean handleBoard(CommandSender sender, String[] args) {
        if (!sender.hasPermission("quest.admin")) { sender.sendMessage("§c无权限。"); return true; }
        if (!(sender instanceof Player player)) { sender.sendMessage("§c玩家专用命令。"); return true; }
        if (args.length < 2) {
            player.sendMessage("§c用法: /quest board create <center|display> 或 /quest board remove");
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "create" -> handleBoardCreate(player, args);
            case "remove" -> handleBoardRemove(player);
            default -> { player.sendMessage("§c用法: /quest board create <center|display> 或 /quest board remove"); yield true; }
        };
    }

    private boolean handleBoardCreate(Player player, String[] args) {
        if (args.length < 3) { player.sendMessage("§c用法: /quest board create <center|display> [personal|town]"); return true; }
        String type = args[2].toLowerCase();
        if (!type.equals("center") && !type.equals("display")) { player.sendMessage("§c类型必须是 center 或 display。"); return true; }

        String questFilter = null;
        if (args.length >= 4) {
            String f = args[3].toLowerCase();
            if (f.equals("personal") || f.equals("town")) questFilter = f;
            else { player.sendMessage("§c过滤器只能是 personal 或 town。"); return true; }
        }

        org.bukkit.block.Block target = player.getTargetBlockExact(5);
        if (target == null || target.getType().isAir()) { player.sendMessage("§c请对准一个方块！"); return true; }

        org.bukkit.block.BlockFace facing = player.getFacing().getOppositeFace();
        org.bukkit.Location signLoc = BoardListener.placeWallSign(target.getLocation(), facing, type);
        Board board = boardManager.createBoard(signLoc, type, QuestFilter.fromString(questFilter));
        String label = questFilter != null ? "(" + questFilter + ")" : "";
        player.sendMessage("§a" + (type.equals("center") ? "中央" : "显示") + "告示牌已创建！" + label + " ID: " + board.id());
        return true;
    }

    private boolean handleBoardRemove(Player player) {
        org.bukkit.block.Block target = player.getTargetBlockExact(5);
        if (target == null) { player.sendMessage("§c请对准委托栏告示牌！"); return true; }
        Board removed = boardManager.removeBoard(target.getLocation());
        if (removed == null) { player.sendMessage("§c这里没有委托栏告示牌。"); return true; }
        target.setType(Material.AIR);
        player.sendMessage("§e委托栏已移除。");
        return true;
    }

    private boolean handlePublish(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§c玩家专用命令。"); return true; }
        plugin.getBoardMenuHandler().startPublish(player);
        return true;
    }

    private boolean handleCreateSub(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§c玩家专用命令。"); return true; }
        if (args.length < 2) return true;
        return switch (args[1].toLowerCase()) {
            case "mat" -> { plugin.getBoardMenuHandler().startMaterialCreation(player); yield true; }
            case "build" -> { plugin.getBoardMenuHandler().startBuildCreation(player); yield true; }
            case "tmat" -> { plugin.getBoardMenuHandler().startTownMaterialCreation(player); yield true; }
            case "tbuild" -> { plugin.getBoardMenuHandler().startTownBuildCreation(player); yield true; }
            case "confirm" -> { plugin.getBoardMenuHandler().confirmPublish(player); yield true; }
            case "cancel" -> { plugin.getBoardMenuHandler().cancelPublish(player); yield true; }
            default -> true;
        };
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== 委托系统 ===");
        sender.sendMessage("§6/quest list §7- 查看可接取委托");
        sender.sendMessage("§6/quest accept <ID前8位> §7- 接取委托");
        sender.sendMessage("§6/quest warehouse <ID前8位> §7- 打开委托仓库");
        sender.sendMessage("§6/quest my §7- 查看我的委托");
        sender.sendMessage("§6右键委托栏告示牌 §7- 发布/查看/撤回委托");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("list", "accept", "warehouse", "board", "my").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        return Collections.emptyList();
    }
}
