package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestItem;
import com.xinantown.quest.model.QuestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        Quest q = new Quest(id, "测试", UUID.randomUUID(), "TownA", true,
                List.of(new QuestItem("DIAMOND", 64)), 1000, 500,
                99999, 99999, QuestStatus.OPEN, null, null, false, 0);

        mgr.saveAll(List.of(q));
        List<Quest> loaded = mgr.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("测试", loaded.get(0).title());
        assertEquals("DIAMOND", loaded.get(0).items().get(0).material());
    }

    @Test
    void loadEmpty_returnsEmptyList() {
        QuestDataManager mgr = new QuestDataManager(tempDir.resolve("nonexistent.yml").toFile(), Logger.getLogger("test"));
        assertTrue(mgr.loadAll().isEmpty());
    }
}
