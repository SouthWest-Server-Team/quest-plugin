package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Quest scroll item — a BOOK that serves as a key to open the quest warehouse GUI.
 * PDC stores quest_id. Function follows BotScroll.ScrollItem pattern.
 */
public final class QuestScroll {

    private final NamespacedKey keyQuestId;
    private static final Material MATERIAL = Material.BOOK;

    public QuestScroll(QuestPlugin plugin) {
        this.keyQuestId = new NamespacedKey(plugin, "quest_id");
    }

    /** Create a scroll linked to a quest. */
    public ItemStack createScroll(UUID questId, String questTitle, String questDesc) {
        ItemStack item = new ItemStack(MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6委托卷");
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        meta.getPersistentDataContainer().set(keyQuestId,
                PersistentDataType.STRING, questId.toString());

        List<String> lore = new ArrayList<>();
        lore.add("§7委托: §e" + questTitle);
        lore.add("§7" + questDesc);
        lore.add("");
        lore.add("§7右键打开委托仓库");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Check if an item is a quest scroll. */
    public boolean isScroll(ItemStack item) {
        if (item == null || item.getType() != MATERIAL) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(keyQuestId, PersistentDataType.STRING);
    }

    /** Get the quest ID from a scroll, or null. */
    public UUID getQuestId(ItemStack item) {
        if (!isScroll(item)) return null;
        String id = item.getItemMeta().getPersistentDataContainer()
                .get(keyQuestId, PersistentDataType.STRING);
        return id != null ? UUID.fromString(id) : null;
    }

    public static void removeFromInventory(Player player, UUID questId, QuestPlugin plugin) {
        QuestScroll scroll = new QuestScroll(plugin);
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            var item = player.getInventory().getItem(i);
            if (scroll.isScroll(item)) {
                var id = scroll.getQuestId(item);
                if (id != null && id.equals(questId)) {
                    player.getInventory().setItem(i, null);
                }
            }
        }
    }

    /** Update lore with real-time quest status and remaining time. */
    public void updateLore(ItemStack item, Quest quest) {
        if (!isScroll(item)) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        long remainingMs = quest.completeDeadline() - System.currentTimeMillis();
        long days = Math.max(0, remainingMs / 86400000);
        long hours = Math.max(0, (remainingMs % 86400000) / 3600000);

        String statusStr = switch (quest.status()) {
            case ACCEPTED -> "§e进行中";
            case SUBMITTED -> "§b待确认";
            case COMPLETED -> "§a已完成";
            default -> "§7未知";
        };

        List<String> lore = new ArrayList<>();
        lore.add("§7委托: §e" + quest.title());
        lore.add("§7" + quest.description());
        lore.add("§7状态: " + statusStr);
        if (days > 0) lore.add("§7剩余: §e" + days + "天" + hours + "小时");
        else if (hours > 0) lore.add("§7剩余: §e" + hours + "小时");
        else lore.add("§7剩余: §c即将到期");
        lore.add("");
        lore.add("§7右键打开委托仓库");
        meta.setLore(lore);
        item.setItemMeta(meta);
    }
}
