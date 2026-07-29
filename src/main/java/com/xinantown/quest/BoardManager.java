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
            groupId = findOrCreateGroup(loc);
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
     * BFS: find existing display boards adjacent (4-direction) to the given location.
     * Assign to an existing group, or create a new group.
     */
    private int findOrCreateGroup(Location loc) {
        Set<String> visited = new HashSet<>();
        for (Board existing : boards.values()) {
            if (!existing.isDisplay()) continue;
            if (isAdjacent(loc, existing)) {
                return existing.groupId();
            }
        }
        return nextGroupId++;
    }

    private boolean isAdjacent(Location loc, Board board) {
        if (!loc.getWorld().getName().equals(board.world())) return false;
        int dx = Math.abs(loc.getBlockX() - board.x());
        int dz = Math.abs(loc.getBlockZ() - board.z());
        return loc.getBlockY() == board.y() && dx + dz == 1;
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
