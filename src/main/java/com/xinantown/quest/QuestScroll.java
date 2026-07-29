package com.xinantown.quest;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
    public ItemStack createScroll(UUID questId, String questTitle) {
        ItemStack item = new ItemStack(MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6委托卷");
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        meta.getPersistentDataContainer().set(keyQuestId,
                PersistentDataType.STRING, questId.toString());

        List<String> lore = new ArrayList<>();
        lore.add("§7委托: §e" + questTitle);
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
}
