package com.xinantown.quest.model;

import org.bukkit.Location;

/**
 * A quest bulletin board placed in the world.
 *
 * @param id       unique board ID
 * @param world    world name
 * @param x        block X coordinate
 * @param y        block Y coordinate
 * @param z        block Z coordinate
 * @param type     "center" or "display"
 * @param groupId  group ID for linked display boards (0 if center or ungrouped)
 */
public record Board(
        String id,
        String world,
        int x, int y, int z,
        String type,
        int groupId,
        QuestFilter questFilter) {  // ALL, PERSONAL, or TOWN

    public boolean isCenter() { return "center".equals(type); }
    public boolean isDisplay() { return "display".equals(type); }

    public boolean isAt(Location loc) {
        return world.equals(loc.getWorld().getName())
                && x == loc.getBlockX()
                && y == loc.getBlockY()
                && z == loc.getBlockZ();
    }
}
