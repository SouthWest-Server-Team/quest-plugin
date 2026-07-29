package com.xinantown.quest.event;

import com.xinantown.quest.model.Quest;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Base event for quest state changes. */
public abstract class QuestEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    protected final Quest quest;

    public QuestEvent(Quest quest) { super(); this.quest = quest; }
    public Quest getQuest() { return quest; }
    public static HandlerList getHandlerList() { return handlers; }
    @Override public HandlerList getHandlers() { return handlers; }
}
