package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestItem;
import com.xinantown.quest.model.QuestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QuestTest {

    private final UUID pubId = UUID.randomUUID();
    private final UUID accId = UUID.randomUUID();
    private final long future = System.currentTimeMillis() + 86400000;

    private Quest newQuest() {
        return new Quest(UUID.randomUUID(), "测试委托", pubId, "TownA",
                true, List.of(new QuestItem("DIAMOND", 64)), 1000.0, 500.0,
                future, future + 3600000, QuestStatus.OPEN,
                null, null, false, 0);
    }

    @Test void initialStatus_isOpen() { assertEquals(QuestStatus.OPEN, newQuest().status()); }
    @Test void accept_changesStatus() { assertEquals(QuestStatus.ACCEPTED, newQuest().accept(accId, "TownB", true, future).status()); }
    @Test void submit_changesStatus() { assertEquals(QuestStatus.SUBMITTED, newQuest().accept(accId, "B", true, future).submit().status()); }
    @Test void complete_changesStatus() { assertEquals(QuestStatus.COMPLETED, newQuest().accept(accId, "B", true, future).submit().complete().status()); }
    @Test void cancel_changesStatus() { assertEquals(QuestStatus.CANCELLED, newQuest().cancel().status()); }

    @Test void reject_belowMax_returnsToAccepted() {
        Quest q = newQuest().accept(accId, "B", true, future).submit();
        Quest r = q.reject(5);
        assertEquals(QuestStatus.ACCEPTED, r.status());
        assertEquals(1, r.rejectCount());
    }

    @Test void reject_atMax_cancels() {
        Quest q = newQuest();
        for (int i = 0; i < 4; i++) q = q.accept(accId, "B", true, future).submit().reject(5);
        Quest r = q.submit().reject(5);
        assertEquals(QuestStatus.CANCELLED, r.status());
        assertEquals(5, r.rejectCount());
    }

    @Test void expired_openQuest() { assertTrue(new Quest(UUID.randomUUID(), "t", pubId, "A", true, List.of(), 1, 1, 0, 0, QuestStatus.OPEN, null, null, false, 0).isExpired()); }
    @Test void notExpired_acceptedWithFutureDeadline() { assertFalse(newQuest().accept(accId, "B", true, future).isExpired()); }
}
