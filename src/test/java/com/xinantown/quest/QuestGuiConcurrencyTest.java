package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestGuiConcurrencyTest {
    @TempDir Path folder;

    private Quest quest(UUID publisher, UUID acceptor) {
        return new Quest(UUID.randomUUID(), "Task", publisher, "Publisher", false, "material", "stone",
                100, 20, Long.MAX_VALUE, Long.MAX_VALUE, QuestStatus.ACCEPTED, acceptor, "Acceptor", false, 0);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Quest> map(QuestGuiManager manager, String name) throws Exception {
        var field = QuestGuiManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<UUID, Quest>) field.get(manager);
    }

    @Test
    void cancelledQuestCannotBeSubmittedOrAutosavedFromAnOldWindow() throws Exception {
        UUID publisher = UUID.randomUUID(), acceptor = UUID.randomUUID();
        Quest stale = quest(publisher, acceptor);
        QuestDataManager data = new QuestDataManager(folder.resolve("quests.yml").toFile(), Logger.getAnonymousLogger());
        data.saveAll(List.of(stale.cancel()));
        QuestPlugin plugin = mock(QuestPlugin.class);
        WarehouseManager warehouse = mock(WarehouseManager.class);
        when(plugin.getDataManager()).thenReturn(data);
        when(plugin.getWarehouseManager()).thenReturn(warehouse);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(acceptor);
        InventoryView view = mock(InventoryView.class);
        when(view.getTitle()).thenReturn("委托仓库");
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getView()).thenReturn(view);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(mock(ServicesManager.class));
            QuestGuiManager manager = new QuestGuiManager(plugin);
            map(manager, "openGuis").put(acceptor, stale);
            manager.onInventoryClick(click);
            assertEquals(QuestStatus.CANCELLED, data.loadAll().getFirst().status());
            verify(click).setCancelled(true);
            verify(player).closeInventory();
            // A stale close event must not recreate a warehouse after cancellation either.
            map(manager, "openGuis").put(acceptor, stale);
            InventoryCloseEvent close = mock(InventoryCloseEvent.class);
            when(close.getPlayer()).thenReturn(player);
            manager.onInventoryClose(close);
            verifyNoInteractions(warehouse);
        }
    }

    @Test
    void rejectIsScheduledAndReadsCurrentListAfterAnotherTaskIsPublished() throws Exception {
        verifyScheduledReject(false);
    }

    @Test
    void queuedRejectCannotReviveAQuestCancelledBeforeMainThreadExecution() throws Exception {
        verifyScheduledReject(true);
    }

    private void verifyScheduledReject(boolean cancelled) throws Exception {
        UUID publisher = UUID.randomUUID(), acceptor = UUID.randomUUID();
        Quest submitted = quest(publisher, acceptor).submit();
        QuestDataManager data = new QuestDataManager(folder.resolve("quests.yml").toFile(), Logger.getAnonymousLogger());
        data.saveAll(List.of(submitted));
        QuestPlugin plugin = mock(QuestPlugin.class);
        when(plugin.getDataManager()).thenReturn(data);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(publisher);
        AsyncPlayerChatEvent chat = mock(AsyncPlayerChatEvent.class);
        when(chat.getPlayer()).thenReturn(player);
        when(chat.getMessage()).thenReturn("Please retry");
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AtomicReference<Runnable> task = new AtomicReference<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            task.set(call.getArgument(1));
            return null;
        });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(mock(ServicesManager.class));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            QuestGuiManager manager = new QuestGuiManager(plugin);
            map(manager, "pendingReject").put(publisher, submitted);
            manager.onChat(chat);
            assertEquals(submitted, data.loadAll().getFirst()); // Async event did not write anything.
            Quest second = quest(UUID.randomUUID(), UUID.randomUUID());
            data.saveAll(List.of(cancelled ? submitted.cancel() : submitted, second));
            task.get().run();
            assertEquals(2, data.loadAll().size());
            assertEquals(second, data.loadAll().get(1));
            assertEquals(cancelled ? QuestStatus.CANCELLED : QuestStatus.ACCEPTED, data.loadAll().getFirst().status());
            assertFalse(manager.hasPendingInput(player));
            verify(chat).setCancelled(true);
        }
    }
}
