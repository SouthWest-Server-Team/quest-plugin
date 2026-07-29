package com.xinantown.quest;

import com.xinantown.quest.model.Board;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages quest bulletin board placement and persistence.
 */
public class BoardManager {

    private final Logger logger;
    private final File file;
    private final Map<String, Board> boards = new LinkedHashMap<>(); // id → board
    private int nextId = 1;
    private int nextGroupId = 1;

    public BoardManager(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "boards.yml");
        this.logger = logger;
        load();
    }

    // ==================== CRUD ====================

    /** Place a new board at the given location. */
    public Board createBoard(Location loc, String type) {
        String id = "board_" + (nextId++);
        int groupId = 0;
        if ("display".equals(type)) {
            groupId = nextGroupId++; // temporary, will be recalculated
        }
        Board board = new Board(id, loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), type, groupId);
        boards.put(id, board);
        save();
        return board;
    }

    /** Remove a board at the given location. Returns the removed board or null. */
    public Board removeBoard(Location loc) {
        for (var e : boards.entrySet()) {
            if (e.getValue().isAt(loc)) {
                Board removed = boards.remove(e.getKey());
                save();
                return removed;
            }
        }
        return null;
    }

    /** Find a board at the given location, or null. */
    public Board findBoard(Location loc) {
        for (Board b : boards.values()) {
            if (b.isAt(loc)) return b;
        }
        return null;
    }

    /** Get all display boards in a group. */
    public List<Board> getGroupBoards(int groupId) {
        List<Board> result = new ArrayList<>();
        for (Board b : boards.values()) {
            if (b.type().equals("display") && b.groupId() == groupId) result.add(b);
        }
        return result;
    }

    /** Get all display boards. */
    public List<Board> getDisplayBoards() {
        List<Board> result = new ArrayList<>();
        for (Board b : boards.values()) {
            if (b.isDisplay()) result.add(b);
        }
        return result;
    }

    /** Get all center boards. */
    public List<Board> getCenterBoards() {
        List<Board> result = new ArrayList<>();
        for (Board b : boards.values()) {
            if (b.isCenter()) result.add(b);
        }
        return result;
    }

    // ==================== Group detection ====================

    /**
     * Recalculate all display board groups using BFS ripple.
     * Starting from each unvisited display board, flood 4-directionally
     * to find all connected display boards. Each connected component gets a unique groupId.
     */
    public void recalculateGroups() {
        var displayBoards = getDisplayBoards();
        Set<String> visited = new HashSet<>();
        int groupId = 1;

        for (Board start : displayBoards) {
            String key = start.world() + ":" + start.x() + ":" + start.y() + ":" + start.z();
            if (visited.contains(key)) continue;

            // BFS from this start board
            Queue<Board> queue = new java.util.LinkedList<>();
            queue.add(start);
            visited.add(key);

            while (!queue.isEmpty()) {
                Board current = queue.poll();
                // Update group
                Board updated = new Board(current.id(), current.world(),
                        current.x(), current.y(), current.z(), current.type(), groupId);
                boards.put(current.id(), updated);

                // Check 4 neighbors
                for (Board neighbor : displayBoards) {
                    String nKey = neighbor.world() + ":" + neighbor.x() + ":" + neighbor.y() + ":" + neighbor.z();
                    if (visited.contains(nKey)) continue;
                    if (isAdjacentBoard(current, neighbor)) {
                        visited.add(nKey);
                        queue.add(neighbor);
                    }
                }
            }
            groupId++;
        }
        nextGroupId = groupId;
        save();
    }

    private boolean isAdjacentBoard(Board a, Board b) {
        if (!a.world().equals(b.world())) return false;
        int dx = Math.abs(a.x() - b.x());
        int dz = Math.abs(a.z() - b.z());
        return a.y() == b.y() && dx + dz == 1;
    }

    // ==================== Persistence ====================

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        nextId = cfg.getInt("_next_id", 1);
        nextGroupId = cfg.getInt("_next_group_id", 1);

        ConfigurationSection boardsSec = cfg.getConfigurationSection("boards");
        if (boardsSec == null) return;

        for (String id : boardsSec.getKeys(false)) {
            ConfigurationSection s = boardsSec.getConfigurationSection(id);
            if (s == null) continue;
            Board board = new Board(id,
                    s.getString("world", "world"),
                    s.getInt("x"), s.getInt("y"), s.getInt("z"),
                    s.getString("type", "display"),
                    s.getInt("group_id", 0));
            boards.put(id, board);
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("_next_id", nextId);
        cfg.set("_next_group_id", nextGroupId);

        ConfigurationSection boardsSec = cfg.createSection("boards");
        for (var e : boards.entrySet()) {
            ConfigurationSection s = boardsSec.createSection(e.getKey());
            Board b = e.getValue();
            s.set("world", b.world());
            s.set("x", b.x()); s.set("y", b.y()); s.set("z", b.z());
            s.set("type", b.type());
            s.set("group_id", b.groupId());
        }

        try { cfg.save(file); } catch (IOException ex) {
            logger.severe("Failed to save boards.yml: " + ex.getMessage());
        }
    }
}
