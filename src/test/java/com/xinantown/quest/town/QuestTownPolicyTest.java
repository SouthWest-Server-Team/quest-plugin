package com.xinantown.quest.town;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 城邦隔离规则（纯函数，不接触 Bukkit / 交互层运行时）。
 *
 * <p>重点钉住三件事：
 * <ol>
 *   <li>本城邦看不到别城邦的城邦委托（列表与告示牌都要）；</li>
 *   <li>别的城邦的成员接不了本城邦的委托（代表权）；</li>
 *   <li>改造前落盘、没有归属城邦的城邦委托：没有任何人能接、不出现在任何板子上，但也不是被删掉的。</li>
 * </ol>
 */
class QuestTownPolicyTest {

    private static final String TOWN_A_ID = UUID.randomUUID().toString();
    private static final String TOWN_B_ID = UUID.randomUUID().toString();

    private static final PlayerTownView VIEW_A = PlayerTownView.of(TOWN_A_ID, "Alpha", true);
    private static final PlayerTownView VIEW_B = PlayerTownView.of(TOWN_B_ID, "Beta", true);
    /** 站在别城的领地上（不是家城邦）。 */
    private static final PlayerTownView VIEW_A_VISITING = PlayerTownView.of(TOWN_A_ID, "Alpha", false);

    private static Quest townQuest(String townId, String townName) {
        long future = System.currentTimeMillis() + 86400000L;
        return new Quest(UUID.randomUUID(), "城邦委托", UUID.randomUUID(), "Mayor", true,
                "material", "钻石 × 64", 1000, 500, future, future,
                QuestStatus.OPEN, null, null, false, 0, townId, townName);
    }

    private static Quest personalQuest() {
        long future = System.currentTimeMillis() + 86400000L;
        return new Quest(UUID.randomUUID(), "个人委托", UUID.randomUUID(), "Someone", false,
                "material", "橡木原木 × 64", 100, 50, future, future,
                QuestStatus.OPEN, null, null, false, 0, null, null);
    }

    // ==================== 公告板/列表隔离 ====================

    @Test
    void ownTownQuest_isVisibleToOwnTown() {
        assertTrue(QuestTownPolicy.visibleInList(townQuest(TOWN_A_ID, "Alpha"), VIEW_A));
    }

    @Test
    void otherTownsQuest_isNotVisible() {
        Quest alphaQuest = townQuest(TOWN_A_ID, "Alpha");
        assertFalse(QuestTownPolicy.visibleInList(alphaQuest, VIEW_B));
        assertFalse(QuestTownPolicy.visibleInList(townQuest(TOWN_B_ID, "Beta"), VIEW_A));
    }

    @Test
    void townQuest_isNotVisibleWhenTownUnknown() {
        Quest alphaQuest = townQuest(TOWN_A_ID, "Alpha");
        assertFalse(QuestTownPolicy.visibleInList(alphaQuest, PlayerTownView.unknown()));
        assertFalse(QuestTownPolicy.visibleInList(alphaQuest, PlayerTownView.outsideTown()));
        assertFalse(QuestTownPolicy.visibleInList(alphaQuest, null));
    }

    @Test
    void personalQuest_isVisibleEverywhere() {
        Quest personal = personalQuest();
        assertTrue(QuestTownPolicy.visibleInList(personal, VIEW_A));
        assertTrue(QuestTownPolicy.visibleInList(personal, VIEW_B));
        assertTrue(QuestTownPolicy.visibleInList(personal, PlayerTownView.unknown()));
        assertTrue(QuestTownPolicy.visibleInList(personal, PlayerTownView.outsideTown()));
    }

    @Test
    void boardShowsOnlyItsOwnTownsQuests() {
        Quest alphaQuest = townQuest(TOWN_A_ID, "Alpha");
        assertTrue(QuestTownPolicy.visibleOnBoard(alphaQuest, TOWN_A_ID));
        assertFalse(QuestTownPolicy.visibleOnBoard(alphaQuest, TOWN_B_ID));
        assertFalse(QuestTownPolicy.visibleOnBoard(alphaQuest, null));
    }

    @Test
    void personalQuest_stillShowsOnEveryBoard() {
        assertTrue(QuestTownPolicy.visibleOnBoard(personalQuest(), TOWN_B_ID));
        assertTrue(QuestTownPolicy.visibleOnBoard(personalQuest(), null));
    }

    // ==================== 代表权（接取） ====================

    @Test
    void ownTownMemberOnOwnLand_canRepresent() {
        assertTrue(QuestTownPolicy.canRepresent(townQuest(TOWN_A_ID, "Alpha"), VIEW_A));
    }

    @Test
    void otherTownsRepresentative_cannotAccept() {
        Quest alphaQuest = townQuest(TOWN_A_ID, "Alpha");
        assertFalse(QuestTownPolicy.canRepresent(alphaQuest, VIEW_B));
        assertFalse(QuestTownPolicy.canRepresent(townQuest(TOWN_B_ID, "Beta"), VIEW_A));
    }

    @Test
    void visitorStandingInTownLand_cannotRepresent() {
        assertFalse(QuestTownPolicy.canRepresent(townQuest(TOWN_A_ID, "Alpha"), VIEW_A_VISITING));
    }

    @Test
    void unknownOrWilderness_cannotRepresent() {
        Quest alphaQuest = townQuest(TOWN_A_ID, "Alpha");
        assertFalse(QuestTownPolicy.canRepresent(alphaQuest, PlayerTownView.unknown()));
        assertFalse(QuestTownPolicy.canRepresent(alphaQuest, PlayerTownView.outsideTown()));
        assertFalse(QuestTownPolicy.canRepresent(alphaQuest, null));
    }

    // ==================== 既有无归属城邦委托 ====================

    @Test
    void legacyUnownedTownQuest_isRecognisedAsUnowned() {
        Quest legacy = townQuest(null, null);
        assertTrue(QuestTownPolicy.isUnownedTownQuest(legacy));
        assertNull(QuestTownPolicy.townKeyOf(legacy));
        assertFalse(legacy.hasTownBinding());
    }

    @Test
    void legacyUnownedTownQuest_isQuarantinedButNotDeleted() {
        Quest legacy = townQuest(null, null);
        assertFalse(QuestTownPolicy.visibleInList(legacy, VIEW_A));
        assertFalse(QuestTownPolicy.visibleInList(legacy, VIEW_B));
        assertFalse(QuestTownPolicy.visibleOnBoard(legacy, TOWN_A_ID));
        assertFalse(QuestTownPolicy.canRepresent(legacy, VIEW_A));
        // 数据本身仍在（隔离 ≠ 删除）：委托对象与它的字段原样保留
        assertEquals(QuestStatus.OPEN, legacy.status());
        assertEquals(1000.0, legacy.reward());
    }

    @Test
    void personalQuest_hasNoTownBinding() {
        Quest personal = personalQuest();
        assertNull(QuestTownPolicy.townKeyOf(personal));
        assertFalse(QuestTownPolicy.isUnownedTownQuest(personal));
        assertFalse(personal.hasTownBinding());
    }
}
