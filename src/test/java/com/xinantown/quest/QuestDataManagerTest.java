package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class QuestDataManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoad_roundtrip() {
        QuestDataManager mgr = new QuestDataManager(tempDir.resolve("quests.yml").toFile(), Logger.getLogger("test"));
        UUID id = UUID.randomUUID();
        Quest q = new Quest(id, "测试委托", UUID.randomUUID(), "TownA", true,
                "material", "钻石 × 64", 1000, 500,
                99999, 99999, QuestStatus.OPEN, null, null, false, 0);

        mgr.saveAll(List.of(q));
        List<Quest> loaded = mgr.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("测试委托", loaded.get(0).title());
        assertEquals("material", loaded.get(0).questType());
        assertEquals("钻石 × 64", loaded.get(0).description());
    }

    @Test
    void loadEmpty_returnsEmptyList() {
        QuestDataManager mgr = new QuestDataManager(tempDir.resolve("nonexistent.yml").toFile(), Logger.getLogger("test"));
        assertTrue(mgr.loadAll().isEmpty());
    }

    @Test
    void changeListener_isInvokedOnEverySave() {
        QuestDataManager mgr = new QuestDataManager(tempDir.resolve("quests.yml").toFile(), Logger.getLogger("test"));
        int[] calls = {0};
        mgr.setChangeListener(() -> calls[0]++);

        mgr.saveAll(List.of());
        mgr.saveAll(List.of());

        assertEquals(2, calls[0]);
    }

    @Test
    void changeListener_failureDoesNotBreakSaving() {
        QuestDataManager mgr = new QuestDataManager(tempDir.resolve("quests.yml").toFile(), Logger.getLogger("test"));
        mgr.setChangeListener(() -> {
            throw new IllegalStateException("listener boom");
        });
        UUID id = UUID.randomUUID();
        Quest q = new Quest(id, "监听器异常委托", UUID.randomUUID(), "TownA", true,
                "material", "钻石 × 64", 1000, 500,
                99999, 99999, QuestStatus.OPEN, null, null, false, 0);

        mgr.saveAll(List.of(q));

        assertEquals(1, mgr.loadAll().size());
        assertEquals("监听器异常委托", mgr.loadAll().get(0).title());
    }

    @Test
    void changeListener_canBeRemoved() {
        QuestDataManager mgr = new QuestDataManager(tempDir.resolve("quests.yml").toFile(), Logger.getLogger("test"));
        int[] calls = {0};
        mgr.setChangeListener(() -> calls[0]++);
        mgr.saveAll(List.of());
        mgr.setChangeListener(null);
        mgr.saveAll(List.of());

        assertEquals(1, calls[0]);
    }

    // ==================== A10 城邦绑定：持久化与历史数据兼容 ====================

    @Test
    void townBinding_roundtrip() {
        QuestDataManager mgr = new QuestDataManager(tempDir.resolve("quests.yml").toFile(), Logger.getLogger("test"));
        UUID townId = UUID.randomUUID();
        Quest bound = new Quest(UUID.randomUUID(), "城邦委托", UUID.randomUUID(), "Alpha", true,
                "material", "钻石 × 64", 1000, 500, 99999, 99999, QuestStatus.OPEN,
                null, null, false, 0, townId.toString(), "Alpha");

        mgr.saveAll(List.of(bound));
        Quest loaded = mgr.loadAll().get(0);

        assertEquals(townId.toString(), loaded.townId());
        assertEquals("Alpha", loaded.townName());
        assertTrue(loaded.hasTownBinding());
        assertTrue(com.xinantown.quest.town.QuestTownPolicy.visibleInList(loaded,
                com.xinantown.quest.town.PlayerTownView.of(townId.toString(), "Alpha", true)));
    }

    /**
     * 改造前落盘的城邦委托（没有 townId/townName 键）必须被读出来并保留，不许静默丢弃；
     * 读出来后按「无归属城邦委托」处理。
     */
    @Test
    void legacyTownQuestWithoutBinding_isLoadedAndKept() {
        File file = tempDir.resolve("quests.yml").toFile();
        UUID id = UUID.randomUUID();
        YamlConfiguration cfg = new YamlConfiguration();
        ConfigurationSection sec = cfg.createSection("quests");
        ConfigurationSection q = sec.createSection(id.toString());
        q.set("title", "历史城邦委托");
        q.set("publisherId", UUID.randomUUID().toString());
        q.set("publisherName", "OldMayor");
        q.set("isTownQuest", true);
        q.set("questType", "material");
        q.set("description", "钻石 × 8");
        q.set("reward", 800.0);
        q.set("deposit", 400.0);
        q.set("acceptDeadline", 99999L);
        q.set("completeDeadline", 99999L);
        q.set("status", "OPEN");
        q.set("isTownAcceptor", false);
        q.set("rejectCount", 0);
        try { cfg.save(file); } catch (java.io.IOException e) { throw new IllegalStateException(e); }

        QuestDataManager mgr = new QuestDataManager(file, Logger.getLogger("test"));
        List<Quest> loaded = mgr.loadAll();

        assertEquals(1, loaded.size(), "历史数据不许在读取时消失");
        Quest legacy = loaded.get(0);
        assertTrue(legacy.isTownQuest());
        assertNull(legacy.townId());
        assertTrue(com.xinantown.quest.town.QuestTownPolicy.isUnownedTownQuest(legacy));
        assertFalse(legacy.hasTownBinding());
        assertEquals(800.0, legacy.reward());

        // 也不许在保存时被抹掉：再存一次仍在，且仍无归属
        mgr.saveAll(loaded);
        assertEquals(1, mgr.loadAll().size());
        assertTrue(com.xinantown.quest.town.QuestTownPolicy.isUnownedTownQuest(mgr.loadAll().get(0)));
    }
}
