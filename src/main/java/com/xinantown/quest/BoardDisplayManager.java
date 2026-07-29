package com.xinantown.quest;

import com.xinantown.quest.model.Board;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Manages display board rotation, grouping, and the board→quest mapping.
 */
public class BoardDisplayManager {

    private final QuestPlugin plugin;
    private final BoardManager boardManager;
    private final QuestDataManager dataManager;
    private final Map<Integer, Integer> rotationIndex = new HashMap<>();
    private final Map<String, com.xinantown.quest.model.Quest> boardQuestMap = new HashMap<>();
    private int rotationSeconds;
    private BukkitTask displayTask;

    public BoardDisplayManager(QuestPlugin plugin, BoardManager boardManager, QuestDataManager dataManager) {
        this.plugin = plugin;
        this.boardManager = boardManager;
        this.dataManager = dataManager;
        this.rotationSeconds = plugin.getConfig().getInt("board-rotation-seconds", 30);
    }

    public void start() {
        displayTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            var quests = dataManager.loadAll().stream()
                    .filter(q -> q.status() == com.xinantown.quest.model.QuestStatus.OPEN)
                    .toList();
            if (System.currentTimeMillis() / 1000 % 30 == 0) boardManager.recalculateGroups();
            rotateDisplays(quests);
        }, 40L, 40L);
    }

    public void stop() { if (displayTask != null) displayTask.cancel(); }

    public void refreshNow() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            var quests = dataManager.loadAll().stream()
                    .filter(q -> q.status() == com.xinantown.quest.model.QuestStatus.OPEN).toList();
            rotateDisplays(quests);
        });
    }

    public com.xinantown.quest.model.Quest getQuestForBoard(String boardId) {
        return boardQuestMap.get(boardId);
    }

    private void rotateDisplays(java.util.List<com.xinantown.quest.model.Quest> quests) {
        long seconds = System.currentTimeMillis() / 1000;
        for (Board board : boardManager.getDisplayBoards()) {
            int groupId = board.groupId();
            var groupBoards = boardManager.getGroupBoards(groupId);
            if (groupBoards.isEmpty()) groupBoards = List.of(board);
            int position = groupBoards.indexOf(board);
            if (quests.isEmpty() || position >= quests.size()) {
                clearSign(board);
                continue;
            }
            int index = rotationIndex.getOrDefault(groupId, 0);
            int questIndex = (index + position) % quests.size();
            var quest = quests.get(questIndex);
            boardQuestMap.put(board.id(), quest);
            updateSign(board, quest);
            if (board.equals(groupBoards.get(0)) && seconds % rotationSeconds == 0) {
                rotationIndex.put(groupId, (index + 1) % quests.size());
            }
        }
    }

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
        sign.update();
    }

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
        sign.update();
    }

    private String truncate(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
