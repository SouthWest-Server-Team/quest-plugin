package com.xinantown.quest.event;

import com.xinantown.quest.model.Quest;

/** Fired when a quest is completed, cancelled, or terminated. */
public class QuestCompletedEvent extends QuestEvent {
    public QuestCompletedEvent(Quest quest) { super(quest); }
}
