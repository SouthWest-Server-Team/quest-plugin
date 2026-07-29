package com.xinantown.quest;

import com.xinantown.quest.model.Board;
import com.xinantown.quest.model.QuestFilter;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages display board rotation, grouping, and the board→quest mapping.
 * 
 * 功能:
 * 1) 告示牌PDC存储委托ID (NamespacedKey "quest_id")
 * 2) 告示牌分组2D网格轮换 + 完成后空位置换（空位集中在尾部）
 * 3) 每组独立的轮换队列
 */
public class BoardDisplayManager {

    private final QuestPlugin plugin;
    private final BoardManager boardManager;
    private final QuestDataManager dataManager;
    /** PDC key: 告示牌方块上存储委托ID */
    private final NamespacedKey questIdKey;
    private final Map<String, com.xinantown.quest.model.Quest> boardQuestMap = new HashMap<>();
    /** 每组独立的委托队列：groupId → List<Quest> */
    private final Map<Integer, List<com.xinantown.quest.model.Quest>> groupQuestQueue = new HashMap<>();
    private int rotationSeconds;
    private int urgentHours, warnHours, urgentCopies, warnCopies;
    private BukkitTask displayTask;

    public BoardDisplayManager(QuestPlugin plugin, BoardManager boardManager, QuestDataManager dataManager) {
        this.plugin = plugin;
        this.boardManager = boardManager;
        this.dataManager = dataManager;
        this.rotationSeconds = plugin.getConfig().getInt("board-rotation-seconds", 30);
        this.urgentHours = plugin.getConfig().getInt("board-urgent-hours", 1);
        this.warnHours = plugin.getConfig().getInt("board-warn-hours", 24);
        this.urgentCopies = plugin.getConfig().getInt("board-urgent-copies", 3);
        this.warnCopies = plugin.getConfig().getInt("board-warn-copies", 2);
        this.questIdKey = new NamespacedKey(plugin, "quest_id");
    }

    public void start() {
        displayTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            var quests = dataManager.loadAll().stream()
                    .filter(q -> q.status() == com.xinantown.quest.model.QuestStatus.OPEN)
                    .toList();
            rotateDisplays(quests);
        }, 40L, 40L);
    }

    public void stop() { if (displayTask != null) displayTask.cancel(); }

    /**
     * 立即刷新：重算分组、重建队列、重绘告示牌。
     * 在委托完成/取消时调用，确保空位被置换到尾部。
     */
    public void refreshNow() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            var quests = dataManager.loadAll().stream()
                    .filter(q -> q.status() == com.xinantown.quest.model.QuestStatus.OPEN).toList();
            boardManager.recalculateGroups();
            rebuildGroupQueues(quests);
            rotateDisplays(quests);
        });
    }

    /** 通过boardId从内存Map查找委托（fallback方式） */
    public com.xinantown.quest.model.Quest getQuestForBoard(String boardId) {
        return boardQuestMap.get(boardId);
    }

    /** 从告示牌方块的PDC读取委托ID（需求1: 方块级NBT存储） */
    public java.util.UUID readQuestIdFromBlock(Block block) {
        if (!(block.getState() instanceof Sign sign)) return null;
        String idStr = sign.getPersistentDataContainer().get(questIdKey, PersistentDataType.STRING);
        return idStr != null ? java.util.UUID.fromString(idStr) : null;
    }

    // ==================== 分组队列管理 ====================

    /**
     * 为每个组重建委托队列。
     * 所有OPEN委托进入每个组的队列（每组独立轮换，互不干扰）。
     * 已完成的委托自动不在池中，空位自然置换到队尾。
     */
    private void rebuildGroupQueues(java.util.List<com.xinantown.quest.model.Quest> openQuests) {
        groupQuestQueue.clear();
        var groups = boardManager.getDisplayBoards().stream()
                .collect(Collectors.groupingBy(Board::groupId));
        for (int groupId : groups.keySet()) {
            groupQuestQueue.put(groupId, new ArrayList<>(replicateWeighted(openQuests)));
        }
    }

    private java.util.List<com.xinantown.quest.model.Quest> replicateWeighted(
            java.util.List<com.xinantown.quest.model.Quest> quests) {
        java.util.List<com.xinantown.quest.model.Quest> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        long urgentMs = urgentHours * 3600000L;
        long warnMs = warnHours * 3600000L;
        for (var q : quests) {
            long remaining = q.acceptDeadline() - now;
            int copies = remaining < urgentMs ? urgentCopies
                    : remaining < warnMs ? warnCopies : 1;
            for (int i = 0; i < copies; i++) result.add(q);
        }
        return result;
    }

    /**
     * 2D网格排序：将组内告示牌按行主序排列。
     * 以组内最小x,z为原点(0,0)，每块牌的相对坐标 = (x - minX, z - minZ)，
     * 先按行(z)排序，再按列(x)排序。
     */
    private List<Board> sortByGrid(java.util.List<Board> boards) {
        int minX = boards.stream().mapToInt(Board::x).min().orElse(0);
        int minZ = boards.stream().mapToInt(Board::z).min().orElse(0);
        return boards.stream()
                .sorted(Comparator.<Board, Integer>comparing(b -> b.z() - minZ)
                        .thenComparing(b -> b.x() - minX))
                .toList();
    }

    // ==================== 轮换与显示 ====================

    /**
     * 轮换显示逻辑（需求2）：
     * - 每组独立的委托队列
     * - 按2D网格顺序将队列中的委托分配给告示牌
     * - 第i块牌显示 queue[i]（若i < queue.size()），否则显示"暂无委托"
     * - 达到轮换时间时，队列头移至尾部（旋转）
     * - 已完成的委托不在队列中，空位自动集中在尾部
     */
    private void rotateDisplays(java.util.List<com.xinantown.quest.model.Quest> quests) {
        long seconds = System.currentTimeMillis() / 1000;
        boolean shouldRotate = rotationSeconds > 0 && seconds % rotationSeconds == 0;

        var groups = boardManager.getDisplayBoards().stream()
                .collect(Collectors.groupingBy(Board::groupId));

        // Global dedup: same quest only on one board
        Set<UUID> usedQuestIds = new HashSet<>();

        for (var entry : groups.entrySet()) {
            int groupId = entry.getKey();
            var groupBoards = entry.getValue();
            if (groupBoards.isEmpty()) continue;

            var sorted = sortByGrid(groupBoards);
            var queue = groupQuestQueue.get(groupId);
            if (queue == null) { queue = new ArrayList<>(quests); groupQuestQueue.put(groupId, queue); }

            if (shouldRotate && queue.size() > 1) {
                var first = queue.remove(0);
                queue.add(first);
            }

            for (int i = 0; i < sorted.size(); i++) {
                Board board = sorted.get(i);
                // Per-board filter: respect questFilter
                var filtered = queue.stream()
                        .filter(q -> board.questFilter() == QuestFilter.ALL
                                || (board.questFilter() == QuestFilter.PERSONAL && !q.isTownQuest())
                                || (board.questFilter() == QuestFilter.TOWN && q.isTownQuest()))
                        .filter(q -> !usedQuestIds.contains(q.id()))
                        .toList();
                var available = filtered.isEmpty() ? null : filtered.get(0);
                if (available != null) {
                    usedQuestIds.add(available.id());
                    boardQuestMap.put(board.id(), available);
                    updateSign(board, available);
                } else {
                    boardQuestMap.remove(board.id());
                    clearSign(board);
                }
            }
        }
    }

    // ==================== 告示牌写入（含PDC） ====================

    /**
     * 清除告示牌文本 + 移除PDC中的quest_id（需求1）。
     */
    private void clearSign(Board board) {
        boardQuestMap.remove(board.id());
        var world = Bukkit.getWorld(board.world());
        if (world == null) return;
        Block block = world.getBlockAt(board.x(), board.y(), board.z());
        if (!(block.getState() instanceof Sign sign)) return;
        sign.setLine(0, "§8[委托栏]");
        sign.setLine(1, "§7暂无委托");
        sign.setLine(2, "");
        sign.setLine(3, "");
        sign.getPersistentDataContainer().remove(questIdKey);
        sign.setGlowingText(true);
        sign.update();
    }

    /**
     * 更新告示牌文本 + 写入quest_id到PDC（需求1）。
     */
    private void updateSign(Board board, com.xinantown.quest.model.Quest quest) {
        var world = Bukkit.getWorld(board.world());
        if (world == null) return;
        Block block = world.getBlockAt(board.x(), board.y(), board.z());
        if (!(block.getState() instanceof Sign sign)) return;
        String type = quest.isTownQuest() ? "§b[城邦]" : "§a[个人]";
        sign.setLine(0, type);
        sign.setLine(1, "§6" + truncate(quest.title(), 15));
        sign.setLine(2, "§7报酬: §e$" + String.format("%.0f", quest.reward()));
        sign.setLine(3, "§7右键接取");
        sign.getPersistentDataContainer().set(questIdKey, PersistentDataType.STRING, quest.id().toString());
        sign.setGlowingText(true);
        sign.update();
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
