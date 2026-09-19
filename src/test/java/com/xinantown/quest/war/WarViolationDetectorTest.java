package com.xinantown.quest.war;

import com.xinantown.api.war.WarRecord;
import com.xinantown.api.war.WarStateSnapshot;
import com.xinantown.api.war.WarStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯规则测试：只使用交互层只读契约的不可变 DTO，不接触 Bukkit。
 */
class WarViolationDetectorTest {

    private static final UUID QUEST_ID = UUID.randomUUID();

    private static WarViolationDetector.Candidate quest(String publisherTown, String acceptorTown) {
        return new WarViolationDetector.Candidate(QUEST_ID, "测试委托", publisherTown, acceptorTown);
    }

    private static WarRecord war(String warId, String attacker, String defender, WarStatus status) {
        return new WarRecord(warId, attacker, defender, status);
    }

    private static WarStateSnapshot snapshot(WarRecord... wars) {
        return new WarStateSnapshot(List.of(wars));
    }

    @Test
    void activeWarBetweenQuestTowns_punishesDeclaringTown() {
        var violations = new WarViolationDetector().detect(
                List.of(quest("Alpha", "Beta")),
                snapshot(war("w1", "Alpha", "Beta", WarStatus.ACTIVE)));

        assertEquals(1, violations.size());
        assertEquals("Alpha", violations.get(0).attackerTown());
        assertEquals("w1", violations.get(0).warId());
        assertEquals(QUEST_ID, violations.get(0).questId());
        assertEquals("测试委托", violations.get(0).questTitle());
    }

    @Test
    void declaringTownIsPunishedEvenWhenItIsTheAcceptorSide() {
        var violations = new WarViolationDetector().detect(
                List.of(quest("Alpha", "Beta")),
                snapshot(war("w1", "Beta", "Alpha", WarStatus.ACTIVE)));

        assertEquals(1, violations.size());
        assertEquals("Beta", violations.get(0).attackerTown());
    }

    @Test
    void townNamesAreComparedCaseInsensitively() {
        var violations = new WarViolationDetector().detect(
                List.of(quest("alpha", "BETA")),
                snapshot(war("w1", "Alpha", "Beta", WarStatus.ACTIVE)));

        assertEquals(1, violations.size());
        assertEquals("Alpha", violations.get(0).attackerTown());
    }

    @Test
    void pendingWarIsNotPunished() {
        assertTrue(new WarViolationDetector().detect(
                List.of(quest("Alpha", "Beta")),
                snapshot(war("w1", "Alpha", "Beta", WarStatus.PENDING))).isEmpty());
    }

    @Test
    void endedWarIsNotPunished() {
        assertTrue(new WarViolationDetector().detect(
                List.of(quest("Alpha", "Beta")),
                snapshot(war("w1", "Alpha", "Beta", WarStatus.ENDED))).isEmpty());
    }

    @Test
    void warWithoutTheQuestTownsIsIgnored() {
        assertTrue(new WarViolationDetector().detect(
                List.of(quest("Alpha", "Beta")),
                snapshot(war("w1", "Alpha", "Gamma", WarStatus.ACTIVE))).isEmpty());
    }

    @Test
    void onlyActiveWarAmongManyIsPunished() {
        var violations = new WarViolationDetector().detect(
                List.of(quest("Alpha", "Beta")),
                snapshot(
                        war("w-ended", "Alpha", "Beta", WarStatus.ENDED),
                        war("w-pending", "Alpha", "Beta", WarStatus.PENDING),
                        war("w-active", "Alpha", "Beta", WarStatus.ACTIVE)));

        assertEquals(1, violations.size());
        assertEquals("w-active", violations.get(0).warId());
    }

    @Test
    void emptySnapshotYieldsNoViolations() {
        assertTrue(new WarViolationDetector().detect(
                List.of(quest("Alpha", "Beta")), WarStateSnapshot.empty()).isEmpty());
    }

    @Test
    void nullSnapshotYieldsNoViolations() {
        assertTrue(new WarViolationDetector().detect(List.of(quest("Alpha", "Beta")), null).isEmpty());
    }

    @Test
    void emptyCandidatesYieldNoViolations() {
        assertTrue(new WarViolationDetector().detect(
                List.of(), snapshot(war("w1", "Alpha", "Beta", WarStatus.ACTIVE))).isEmpty());
    }

    @Test
    void sameQuestAndWarIsPunishedOnlyOnce() {
        WarViolationDetector detector = new WarViolationDetector();
        List<WarViolationDetector.Candidate> candidates = List.of(quest("Alpha", "Beta"));
        WarStateSnapshot snapshot = snapshot(war("w1", "Alpha", "Beta", WarStatus.ACTIVE));

        assertEquals(1, detector.detect(candidates, snapshot).size());
        assertTrue(detector.detect(candidates, snapshot).isEmpty());
        assertEquals(1, detector.punishedPairCount());
    }

    @Test
    void aNewWarBetweenTheSameTownsIsPunishedAgain() {
        WarViolationDetector detector = new WarViolationDetector();
        List<WarViolationDetector.Candidate> candidates = List.of(quest("Alpha", "Beta"));

        assertEquals(1, detector.detect(candidates,
                snapshot(war("w1", "Alpha", "Beta", WarStatus.ACTIVE))).size());
        assertEquals(1, detector.detect(candidates,
                snapshot(war("w2", "Alpha", "Beta", WarStatus.ACTIVE))).size());
    }

    @Test
    void distinctQuestsArePunishedIndependently() {
        WarViolationDetector detector = new WarViolationDetector();
        UUID otherQuest = UUID.randomUUID();

        var violations = detector.detect(
                List.of(quest("Alpha", "Beta"),
                        new WarViolationDetector.Candidate(otherQuest, "另一条委托", "Alpha", "Beta")),
                snapshot(war("w1", "Alpha", "Beta", WarStatus.ACTIVE)));

        assertEquals(2, violations.size());
        assertEquals(QUEST_ID, violations.get(0).questId());
        assertEquals(otherQuest, violations.get(1).questId());
    }

    @Test
    void nullCandidateEntriesAreSkipped() {
        List<WarViolationDetector.Candidate> candidates = new ArrayList<>();
        candidates.add(null);
        candidates.add(quest("Alpha", "Beta"));

        assertEquals(1, new WarViolationDetector().detect(candidates,
                snapshot(war("w1", "Alpha", "Beta", WarStatus.ACTIVE))).size());
    }

    /**
     * 与改造前的 OR 条件保持一致：同一城镇同时出现在两侧的战争仍算命中。
     */
    @Test
    void sameTownOnBothSidesStillMatches() {
        var violations = new WarViolationDetector().detect(
                List.of(quest("Alpha", "Alpha")),
                snapshot(war("w1", "Alpha", "Alpha", WarStatus.ACTIVE)));

        assertEquals(1, violations.size());
        assertEquals("Alpha", violations.get(0).attackerTown());
    }

    @Test
    void manyQuestsAndWarsKeepTheirOwnPairing() {
        UUID secondQuest = UUID.randomUUID();
        var violations = new WarViolationDetector().detect(
                Arrays.asList(
                        quest("Alpha", "Beta"),
                        new WarViolationDetector.Candidate(secondQuest, "第二条", "Gamma", "Delta"),
                        quest("Epsilon", "Zeta")),
                snapshot(
                        war("w1", "Alpha", "Beta", WarStatus.ACTIVE),
                        war("w2", "Gamma", "Delta", WarStatus.ACTIVE)));

        assertEquals(2, violations.size());
        assertEquals(QUEST_ID, violations.get(0).questId());
        assertEquals("w1", violations.get(0).warId());
        assertEquals(secondQuest, violations.get(1).questId());
        assertEquals("w2", violations.get(1).warId());
    }
}
