package com.xinantown.quest.event;

import com.xinantown.quest.model.Quest;

/** Fired when a quest is accepted. */
public class QuestAcceptedEvent extends QuestEvent {
    public QuestAcceptedEvent(Quest quest) { super(quest); }
}
