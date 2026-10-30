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
 * @param questFilter ALL, PERSONAL, or TOWN
 * @param townId   town this board is bound to (interaction-layer {@code TownId} UUID text); a display
 *                 board only rotates town quests bound to this same town. {@code null} = 未绑定
 *                 （含改造前落盘的板子），此时只轮播个人委托，不再泄露任何城邦的委托。
 */
public record Board(
        String id,
        String world,
        int x, int y, int z,
        String type,
        int groupId,
        QuestFilter questFilter,
        String townId) {

    /** 改造前落盘/未绑定城邦的告示牌兼容构造。 */
    public Board(String id, String world, int x, int y, int z, String type, int groupId,
                 QuestFilter questFilter) {
        this(id, world, x, y, z, type, groupId, questFilter, null);
    }

    public boolean isCenter() { return "center".equals(type); }
    public boolean isDisplay() { return "display".equals(type); }

    /** 是否绑定到某座城邦。 */
    public boolean isTownBound() { return townId != null && !townId.isBlank(); }

    public boolean isAt(Location loc) {
        return world.equals(loc.getWorld().getName())
                && x == loc.getBlockX()
                && y == loc.getBlockY()
                && z == loc.getBlockZ();
    }
}
