package com.xinantown.quest.event;

import com.xinantown.quest.model.Quest;

/** Fired when a new quest is created. */
public class QuestCreatedEvent extends QuestEvent {
    public QuestCreatedEvent(Quest quest) { super(quest); }
}
