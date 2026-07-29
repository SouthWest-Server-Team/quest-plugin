package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestItem;
import com.xinantown.quest.model.QuestStatus;
import com.xinantown.quest.model.Board;
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
            case "create" -> handleCreate(sender, args);
            case "list" -> handleList(sender);
            case "accept" -> handleAccept(sender, args);
            case "warehouse" -> handleWarehouse(sender, args);
            case "board" -> handleBoard(sender, args);
            case "my" -> handleMy(sender);
            default -> { sendHelp(sender); yield true; }
        };
    }

    // ==================== create ====================

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§c玩家专用命令。"); return true; }
        if (args.length < 4) {
            player.sendMessage("§c用法: /quest create [-t] <标题> <材料:数量,...> <报酬> [押金]");
            return true;
        }

        boolean isTownQuest = false;
        int argStart = 1;
        if (args[1].equals("-t")) {
            isTownQuest = true;
            argStart = 2;
            if (args.length < 5) {
                player.sendMessage("§c用法: /quest create -t <标题> <材料:数量,...> <报酬> [押金]");
                return true;
            }
        }

        String title = args[argStart];
        List<QuestItem> items = parseItems(args[argStart + 1]);
        if (items.isEmpty()) { player.sendMessage("§c物品格式: 材料:数量,材料:数量"); return true; }

        double reward;
        try { reward = Double.parseDouble(args[argStart + 2]); } catch (NumberFormatException e) {
            player.sendMessage("§c报酬必须是数字。"); return true;
        }
        if (reward <= 0) { player.sendMessage("§c报酬必须 > 0。"); return true; }

        double deposit = reward;
        if (args.length > argStart + 3) {
            try { deposit = Double.parseDouble(args[argStart + 3]); } catch (NumberFormatException e) {
                player.sendMessage("§c押金必须是数字。"); return true;
            }
        }

        if (isTownQuest) {
            var town = com.palmergames.bukkit.towny.TownyAPI.getInstance().getTown(player);
            if (town == null) { player.sendMessage("§c你不属于任何城邦！"); return true; }
            if (!town.hasMayor() || !town.getMayor().getUUID().equals(player.getUniqueId())) {
                player.sendMessage("§c只有市长才能发布城邦委托！"); return true;
            }
        }

        if (econ == null) { player.sendMessage("§c经济系统未就绪。"); return true; }
        double totalCost = deposit + reward;
        if (!econ.has(player, totalCost)) {
            player.sendMessage("§c余额不足！需要 $" + String.format("%.0f", totalCost) + " (押金+报酬)"); return true;
        }
        econ.withdrawPlayer(player, totalCost);

        long now = System.currentTimeMillis();
        int acceptDays = plugin.getConfig().getInt("accept-deadline-days", 7);
        int completeDays = plugin.getConfig().getInt("complete-deadline-days", 7);
        Quest quest = new Quest(UUID.randomUUID(), title, player.getUniqueId(), player.getName(),
                isTownQuest, items, reward, deposit,
                now + 86400000L * acceptDays, now + 86400000L * completeDays, QuestStatus.OPEN,
                null, null, false, 0);

        List<Quest> all = new ArrayList<>(dataManager.loadAll());
        all.add(quest);
        dataManager.saveAll(all);

        player.sendMessage("§a委托 §6" + title + " §a已创建！押金: $" + String.format("%.0f", deposit));
        return true;
    }

    private List<QuestItem> parseItems(String input) {
        List<QuestItem> items = new ArrayList<>();
        for (String part : input.split(",")) {
            String[] kv = part.split(":", 2);
            if (kv.length != 2) continue;
            try {
                Material mat = Material.valueOf(kv[0].toUpperCase());
                int amount = Integer.parseInt(kv[1]);
                if (amount > 0) items.add(new QuestItem(mat.name(), amount));
            } catch (IllegalArgumentException ignored) {}
        }
        return items;
    }

    // ==================== list ====================

    private boolean handleList(CommandSender sender) {
        List<Quest> all = dataManager.loadAll();
        List<Quest> open = all.stream().filter(q -> q.status() == QuestStatus.OPEN).toList();
        if (open.isEmpty()) { sender.sendMessage("§7当前没有可接取的委托。"); return true; }

        sender.sendMessage("§6=== 可接取委托 (" + open.size() + ") ===");
        for (Quest q : open) {
            String type = q.isTownQuest() ? "§b[城邦]" : "§a[个人]";
            sender.sendMessage(type + " §6" + q.title() + " §7- " + q.publisherName());
            sender.sendMessage("  §7需求: " + formatItems(q.items()) + " §7报酬: $" + String.format("%.0f", q.reward()));
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

        // Town quest: only town mayors/leaders can accept
        if (found.isTownQuest()) {
            var town = com.palmergames.bukkit.towny.TownyAPI.getInstance().getTown(player);
            if (town == null) { player.sendMessage("§c城邦委托只能由城邦接取！"); return true; }
            if (plugin.getViolationManager().isBanned(town.getName())) {
                long hours = (plugin.getViolationManager().getBanEnd(town.getName()) - System.currentTimeMillis()) / 3600000;
                player.sendMessage("§c城邦处于违约状态，剩余 " + hours + " 小时，无法接取城邦委托。");
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

        // Replace in list
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(found.id())) { all.set(i, accepted); break; }
        }
        dataManager.saveAll(all);

        player.sendMessage("§a已接取委托 §6" + found.title() + "§a！押金: $" + String.format("%.0f", deposit));
        // Notify publisher if online
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

    private String formatItems(List<QuestItem> items) {
        return items.stream().map(i -> i.amount() + "x" + i.material()).collect(Collectors.joining(", "));
    }

    // ==================== board ====================

    private boolean handleBoard(CommandSender sender, String[] args) {
        if (!sender.hasPermission("quest.admin")) {
            sender.sendMessage("§c无权限。"); return true;
        }
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c玩家专用命令。"); return true;
        }

        if (args.length < 2) {
            player.sendMessage("§c用法: /quest board create <center|display> 或 /quest board remove");
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "create" -> handleBoardCreate(player, args);
            case "remove" -> handleBoardRemove(player);
            default -> {
                player.sendMessage("§c用法: /quest board create <center|display> 或 /quest board remove");
                yield true;
            }
        };
    }

    private boolean handleBoardCreate(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§c用法: /quest board create <center|display>");
            return true;
        }

        String type = args[2].toLowerCase();
        if (!type.equals("center") && !type.equals("display")) {
            player.sendMessage("§c类型必须是 center 或 display。");
            return true;
        }

        org.bukkit.block.Block target = player.getTargetBlockExact(5);
        if (target == null || target.getType().isAir()) {
            player.sendMessage("§c请对准一个方块！");
            return true;
        }

        org.bukkit.block.BlockFace facing = player.getFacing().getOppositeFace();
        BoardListener.placeWallSign(target.getLocation(), player.getFacing().getOppositeFace());

        Board board = boardManager.createBoard(target.getLocation(), type);
        player.sendMessage("§a" + (type.equals("center") ? "中央" : "显示") + "告示牌已创建！ID: " + board.id());
        return true;
    }

    private boolean handleBoardRemove(Player player) {
        org.bukkit.block.Block target = player.getTargetBlockExact(5);
        if (target == null) {
            player.sendMessage("§c请对准委托栏告示牌！");
            return true;
        }

        Board removed = boardManager.removeBoard(target.getLocation());
        if (removed == null) {
            player.sendMessage("§c这里没有委托栏告示牌。");
            return true;
        }

        target.setType(org.bukkit.Material.AIR);
        player.sendMessage("§e委托栏已移除。");
        return true;
    }

    // ==================== my ====================

    private boolean handleMy(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§c玩家专用命令。"); return true;
        }
        java.util.List<Quest> all = dataManager.loadAll();
        java.util.List<Quest> mine = all.stream()
                .filter(q -> q.publisherId().equals(player.getUniqueId()))
                .toList();

        if (mine.isEmpty()) {
            player.sendMessage("§7你还没有发布过委托。");
            return true;
        }

        player.sendMessage("§6=== 我的委托 (" + mine.size() + ") ===");
        for (Quest q : mine) {
            String status = switch (q.status()) {
                case OPEN -> "§a可接取";
                case ACCEPTED -> "§e进行中";
                case SUBMITTED -> "§b待确认";
                case COMPLETED -> "§7已完成";
                case CANCELLED -> "§c已取消";
            };
            player.sendMessage("§6" + q.title() + " §7- " + status + " §8[" + q.id().toString().substring(0, 8) + "]");
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== 委托系统 ===");
        sender.sendMessage("§6/quest create [-t] <标题> <物品:数量,...> <报酬> [押金] §7- 创建委托(-t=城邦委托)");
        sender.sendMessage("§6/quest list §7- 查看可接取委托");
        sender.sendMessage("§6/quest accept <ID前8位> §7- 接取委托");
        sender.sendMessage("§6/quest warehouse <ID前8位> §7- 打开委托仓库");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("create", "list", "accept", "warehouse", "board").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        return Collections.emptyList();
    }
}
