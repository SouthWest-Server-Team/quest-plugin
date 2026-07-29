package com.xinantown.quest.model;

import java.util.UUID;

/**
 * A quest (委托).
 *
 * @param id              unique quest ID
 * @param title           short description
 * @param publisherId     UUID of publisher
 * @param publisherName   display name of publisher
 * @param isTownQuest     true = town quest, false = personal quest
 * @param questType       "material" or "build"
 * @param description     material: "橡木原木 × 64", build: "城堡建造"
 * @param reward          total payment from publisher
 * @param deposit         upfront deposit (returned on completion)
 * @param acceptDeadline   quest expiry if not accepted (epoch ms)
 * @param completeDeadline submission deadline (epoch ms)
 * @param status          current status
 * @param acceptorId      UUID of acceptor (null until accepted)
 * @param acceptorName    display name of acceptor
 * @param isTownAcceptor  true if acceptor is a town
 * @param rejectCount     times publisher has rejected submission
 */
public record Quest(
        UUID id, String title,
        UUID publisherId, String publisherName, boolean isTownQuest,
        String questType, String description,
        double reward, double deposit,
        long acceptDeadline, long completeDeadline,
        QuestStatus status,
        UUID acceptorId, String acceptorName, boolean isTownAcceptor,
        int rejectCount) {

    public boolean isMaterialQuest() { return "material".equals(questType); }
    public boolean isBuildQuest() { return "build".equals(questType); }

    public Quest accept(UUID acceptorId, String acceptorName, boolean isTown, long newDeadline) {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                questType, description, reward, deposit, acceptDeadline, newDeadline,
                QuestStatus.ACCEPTED, acceptorId, acceptorName, isTown, rejectCount);
    }

    public Quest submit() {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                questType, description, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.SUBMITTED, acceptorId, acceptorName, isTownAcceptor, rejectCount);
    }

    public Quest complete() {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                questType, description, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.COMPLETED, acceptorId, acceptorName, isTownAcceptor, rejectCount);
    }

    public Quest cancel() {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                questType, description, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.CANCELLED, acceptorId, acceptorName, isTownAcceptor, rejectCount);
    }

    public Quest reject(int maxRejects) {
        int newCount = rejectCount + 1;
        if (newCount >= maxRejects) {
            return new Quest(id, title, publisherId, publisherName, isTownQuest,
                    questType, description, reward, deposit, acceptDeadline, completeDeadline,
                    QuestStatus.CANCELLED, acceptorId, acceptorName, isTownAcceptor, newCount);
        }
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                questType, description, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.ACCEPTED, acceptorId, acceptorName, isTownAcceptor, newCount);
    }

    public boolean isExpired() {
        if (status == QuestStatus.OPEN && System.currentTimeMillis() > acceptDeadline) return true;
        if (status == QuestStatus.ACCEPTED && System.currentTimeMillis() > completeDeadline) return true;
        return false;
    }
}
