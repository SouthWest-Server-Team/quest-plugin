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
 * @param townId          owning town's stable identity (interaction-layer {@code TownId} UUID text);
 *                        {@code null} for personal quests and for town quests written before
 *                        town binding existed (无归属的城邦委托)
 * @param townName        owning town's display name, {@code null} when {@code townId} is
 *                        (it is kept because the war/violation bookkeeping is keyed by town name)
 */
public record Quest(
        UUID id, String title,
        UUID publisherId, String publisherName, boolean isTownQuest,
        String questType, String description,
        double reward, double deposit,
        long acceptDeadline, long completeDeadline,
        QuestStatus status,
        UUID acceptorId, String acceptorName, boolean isTownAcceptor,
        int rejectCount,
        String townId, String townName) {

    public boolean isMaterialQuest() { return "material".equals(questType); }
    public boolean isBuildQuest() { return "build".equals(questType); }

    /** 改造前落盘的个人委托兼容：城邦归属为空。 */
    public Quest(UUID id, String title, UUID publisherId, String publisherName, boolean isTownQuest,
                 String questType, String description, double reward, double deposit,
                 long acceptDeadline, long completeDeadline, QuestStatus status,
                 UUID acceptorId, String acceptorName, boolean isTownAcceptor, int rejectCount) {
        this(id, title, publisherId, publisherName, isTownQuest, questType, description, reward, deposit,
                acceptDeadline, completeDeadline, status, acceptorId, acceptorName, isTownAcceptor,
                rejectCount, null, null);
    }

    /** 城邦归属键：改造前落盘的城邦委托与个人委托都返回 {@code null}。 */
    public String townBindingKey() {
        return (isTownQuest && townId != null && !townId.isBlank()) ? townId : null;
    }

    /** 是否绑定到了某座城邦。 */
    public boolean hasTownBinding() {
        return townBindingKey() != null;
    }

    public Quest accept(UUID acceptorId, String acceptorName, boolean isTown, long newDeadline) {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                questType, description, reward, deposit, acceptDeadline, newDeadline,
                QuestStatus.ACCEPTED, acceptorId, acceptorName, isTown, rejectCount, townId, townName);
    }

    public Quest submit() {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                questType, description, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.SUBMITTED, acceptorId, acceptorName, isTownAcceptor, rejectCount, townId, townName);
    }

    public Quest complete() {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                questType, description, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.COMPLETED, acceptorId, acceptorName, isTownAcceptor, rejectCount, townId, townName);
    }

    public Quest cancel() {
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                questType, description, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.CANCELLED, acceptorId, acceptorName, isTownAcceptor, rejectCount, townId, townName);
    }

    public Quest reject(int maxRejects) {
        int newCount = rejectCount + 1;
        if (newCount >= maxRejects) {
            return new Quest(id, title, publisherId, publisherName, isTownQuest,
                    questType, description, reward, deposit, acceptDeadline, completeDeadline,
                    QuestStatus.CANCELLED, acceptorId, acceptorName, isTownAcceptor, newCount, townId, townName);
        }
        return new Quest(id, title, publisherId, publisherName, isTownQuest,
                questType, description, reward, deposit, acceptDeadline, completeDeadline,
                QuestStatus.ACCEPTED, acceptorId, acceptorName, isTownAcceptor, newCount, townId, townName);
    }

    public boolean isExpired() {
        if (status == QuestStatus.OPEN && System.currentTimeMillis() > acceptDeadline) return true;
        if (status == QuestStatus.ACCEPTED && System.currentTimeMillis() > completeDeadline) return true;
        return false;
    }
}
