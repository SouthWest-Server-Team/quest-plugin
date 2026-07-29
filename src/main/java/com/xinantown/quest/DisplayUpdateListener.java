package com.xinantown.quest;

import com.xinantown.quest.event.QuestAcceptedEvent;
import com.xinantown.quest.event.QuestCompletedEvent;
import com.xinantown.quest.event.QuestCreatedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Listens for quest state changes and triggers display refresh.
 * Decouples display updates from business logic.
 */
public class DisplayUpdateListener implements Listener {

    private final BoardDisplayManager displayManager;

    public DisplayUpdateListener(BoardDisplayManager displayManager) {
        this.displayManager = displayManager;
    }

    @EventHandler
    public void onQuestCreated(QuestCreatedEvent event) {
        displayManager.refreshNow();
    }

    @EventHandler
    public void onQuestAccepted(QuestAcceptedEvent event) {
        displayManager.refreshNow();
    }

    @EventHandler
    public void onQuestCompleted(QuestCompletedEvent event) {
        displayManager.refreshNow();
    }
}
