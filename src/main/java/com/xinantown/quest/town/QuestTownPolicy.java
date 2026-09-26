package com.xinantown.quest.town;

import com.xinantown.quest.model.Quest;

/**
 * 委托的城邦归属规则（纯函数，可直接单元测试）。
 *
 * <p>规则：
 * <ul>
 *   <li><b>个人委托</b>：行为完全不变 —— 任何列表/告示牌都看得到，接取校验不走城邦；</li>
 *   <li><b>城邦委托</b>：归属键取自交互层契约的稳定标识（{@code PlayerTownSnapshot.townId()} 的 UUID 文本），
 *       不按城邦名（契约明确说明「名字不是跨模块的键」）；</li>
 *   <li><b>无归属的城邦委托</b>（改造前落盘的历史数据）：不静默丢弃 —— 数据保留在盘上、
 *       发布者仍能在「我的委托」里看到、过期仍会退还押金，但不再出现在任何城的公告板上，
 *       也不允许任何人接取（没有归属就没有合法代表）。</li>
 * </ul>
 */
public final class QuestTownPolicy {

    private QuestTownPolicy() {
    }

    /**
     * 城邦委托的归属城邦键；个人委托或无归属的城邦委托返回 {@code null}。
     */
    public static String townKeyOf(Quest quest) {
        if (quest == null || !quest.isTownQuest()) {
            return null;
        }
        String townId = quest.townId();
        return (townId == null || townId.isBlank()) ? null : townId;
    }

    /** 是否是改造前落盘、没有归属城邦的城邦委托。 */
    public static boolean isUnownedTownQuest(Quest quest) {
        return quest != null && quest.isTownQuest() && townKeyOf(quest) == null;
    }

    /**
     * 列表可见性：个人委托恒可见；城邦委托只有本城邦（看的人此刻站着的城邦）可见；
     * 无归属的城邦委托对谁都不可见（隔离，但数据保留）。
     */
    public static boolean visibleInList(Quest quest, PlayerTownView viewer) {
        if (quest == null) {
            return false;
        }
        if (!quest.isTownQuest()) {
            return true;
        }
        String key = townKeyOf(quest);
        if (key == null) {
            return false;
        }
        return viewer != null && viewer.hasTown() && key.equals(viewer.townId());
    }

    /**
     * 告示牌可见性：个人委托不变；城邦委托只在「绑定到同一城邦的告示牌」上轮播；
     * 未绑定城邦的告示牌（含改造前落盘的板子）不再展示任何城邦委托。
     *
     * @param boardTownId 告示牌绑定的城邦键，{@code null} 表示未绑定
     */
    public static boolean visibleOnBoard(Quest quest, String boardTownId) {
        if (quest == null) {
            return false;
        }
        if (!quest.isTownQuest()) {
            return true;
        }
        String key = townKeyOf(quest);
        if (key == null) {
            return false;
        }
        return key.equals(boardTownId);
    }

    /**
     * 接取/代表权：只有「站在归属城邦领地上、且这座城邦就是自己城邦」的玩家才能代表该城邦接取。
     *
     * <p><b>已知契约缺口</b>：{@code PlayerTownSnapshot} 没有任何角色字段，
     * 因此改造前「只有市长能代表城邦」的角色判定无法经交互层完成；这里退化到契约能给的最强证据 ——
     * 本城邦成员（{@code homeTown}）。该缺口已在回传里如实标注，不自行发明契约。
     */
    public static boolean canRepresent(Quest quest, PlayerTownView viewer) {
        String key = townKeyOf(quest);
        if (key == null) {
            return false;
        }
        if (viewer == null || !viewer.hasTown() || !viewer.homeTown()) {
            return false;
        }
        return key.equals(viewer.townId());
    }
}
