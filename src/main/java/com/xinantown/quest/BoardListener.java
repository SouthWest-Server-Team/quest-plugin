package com.xinantown.quest;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Protects quest board signs from being broken by players.
 * Handles hologram display via YukiNoaAPI.
 */
public class BoardListener implements Listener {

    private final BoardManager boardManager;

    public BoardListener(BoardManager boardManager) {
        this.boardManager = boardManager;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (boardManager.findBoard(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c这个告示牌是委托栏，无法破坏！使用 §6/quest board remove §c移除。");
        }
    }

    /** Restore center board signs on startup. */
    public void restoreCenterBoards() {
        for (var board : boardManager.getCenterBoards()) {
            var world = Bukkit.getWorld(board.world());
            if (world == null) continue;
            Block block = world.getBlockAt(board.x(), board.y(), board.z());
            if (block.getState() instanceof Sign sign) {
                sign.setGlowingText(true);
                sign.setLine(0, "§8[委托栏]");
                sign.setLine(1, "§6中央告示牌");
                sign.setLine(2, "§7右键发布/管理");
                sign.setLine(3, "");
                sign.update();
            }
        }
    }

    /** Place a wall sign on the block the player is looking at, facing the player. */
    public static Location placeWallSign(Location lookAt, BlockFace facing, String type) {
        Block block = lookAt.getBlock();
        Block signBlock = block.getRelative(facing);

        if (!signBlock.getType().isAir()) {
            signBlock = block;
        }

        signBlock.setType(Material.OAK_WALL_SIGN);
        if (signBlock.getBlockData() instanceof Directional dir) {
            dir.setFacing(facing);
            signBlock.setBlockData(dir);
        }

        // Set sign text
        if (signBlock.getState() instanceof Sign sign) {
            String label = type.equals("center") ? "§6中央告示牌" : "§b显示告示牌";
            sign.setLine(0, "§8[委托栏]");
            sign.setLine(1, label);
            sign.setLine(2, type.equals("center") ? "§7右键发布/管理" : "§7右键查看委托");
            sign.setLine(3, "");
            sign.setGlowingText(true);
            sign.update();
        }

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "hologram create line " + (signBlock.getX() + 0.5) + " " + (signBlock.getY() + 1.3) + " " + (signBlock.getZ() + 0.5)
                        + " \"§6[委托栏]\"");
        return signBlock.getLocation();
    }
}
