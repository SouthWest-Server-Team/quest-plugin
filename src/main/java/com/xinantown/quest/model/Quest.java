package com.xinantown.quest.model;

import java.util.List;
import java.util.UUID;

/**
 * A quest (委托). Tracks items, payment, parties, and status.
 *
 * @param id             unique quest ID
 * @param title          short description
 * @param publisherId    UUID of publisher (player or town represented by mayor)
 * @param publisherName  display name of publisher
 * @param isTownQuest    true = town quest, false = personal quest
 * @param items          required items with quantities
 * @param reward         total payment from publisher
 * @param deposit        upfront deposit (returned on completion)
 * @param acceptDeadline  when quest expires if not accepted (epoch ms)
 * @param completeDeadline when quest must be submitted (epoch ms)
 * @param status         current status
 * @param acceptorId     UUID of acceptor (null until accepted)
 * @param acceptorName   display name of acceptor
 * @param isTownAcceptor true if acceptor is a town
 * @param rejectCount    number of times publisher has rejected submission
 */
public record Quest(
        UUID id, String title,
        UUID publisherId, String publisherName, boolean isTownQuest,
        List<QuestItem> items, double reward, double deposit,
        long acceptDeadline, long completeDeadline,
        QuestStatus status,
        UUID acceptorId, String acceptorName, boolean isTownAcceptor,
        int rejectCount) {

    public Quest accept(UUID acceptorId, String acceptorName, boolean isTown, long newCompleteDeadline) {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                items, reward, deposit, acceptDeadline, newCompleteDeadline,
                QuestStatus.ACCEPTED, acceptorId, acceptorName, isTown, rejectCount);
    }

    public Quest submit() {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                items, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.SUBMITTED, acceptorId, acceptorName, isTownAcceptor, rejectCount);
    }

    public Quest complete() {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                items, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.COMPLETED, acceptorId, acceptorName, isTownAcceptor, rejectCount);
    }

    public Quest cancel() {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                items, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.CANCELLED, acceptorId, acceptorName, isTownAcceptor, rejectCount);
    }

    public Quest reject(int maxRejects) {
        int newCount = rejectCount + 1;
        if (newCount >= maxRejects) {
            return new Quest(id, title, publisherId, publisherName, isTownQuest,
                    items, reward, deposit, acceptDeadline, completeDeadline,
                    QuestStatus.CANCELLED, acceptorId, acceptorName, isTownAcceptor, newCount);
        }
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                items, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.ACCEPTED, acceptorId, acceptorName, isTownAcceptor, newCount);
    }

    public boolean isExpired() {
        if (status == QuestStatus.OPEN && System.currentTimeMillis() > acceptDeadline) return true;
        if (status == QuestStatus.ACCEPTED && System.currentTimeMillis() > completeDeadline) return true;
        return false;
    }
}
