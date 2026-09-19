package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
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
}
