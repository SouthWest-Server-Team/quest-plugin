package com.xinantown.quest.town;

import java.util.Objects;

/**
 * quest-plugin 侧对「玩家此刻的城邦」的只读视图。
 *
 * <p>这是交互层契约 {@code com.xinantown.api.towny.PlayerTownSnapshot} 在本插件内的投影，
 * 只保留委托归属判定真正需要的字段，且不含任何交互层类型 —— 这样判定规则可以在没有交互层、
 * 没有服务端的情况下被单元测试。
 *
 * <p>三种状态必须区分（与契约一致）：
 * <ul>
 *   <li>{@link #unknown()} —— 城邦信息算不出来（provider 不可用 / 玩家离线 / 读 Towny 失败）。
 *       契约用 {@code Optional.empty()} 表达，绝不伪装成「野外」；</li>
 *   <li>{@link #wilderness()} —— 玩家站在任何城邦领地之外，这是一个<b>成功的答案</b>；</li>
 *   <li>{@link #of(String, String, boolean)} —— 玩家站在某城邦的领地上，
 *       {@code homeTown} 表示这座城邦同时就是玩家自己的城邦（家城邦）。</li>
 * </ul>
 *
 * @param available  城邦信息是否可用（false = 算不出来，降级）
 * @param wilderness 玩家是否站在所有城邦领地之外
 * @param townId     城邦稳定标识（{@code TownId} 的 UUID 文本），仅当站在城邦领地上时非空
 * @param townName   城邦名（显示用），仅当站在城邦领地上时非空
 * @param homeTown   脚下城邦是否就是玩家自己的城邦
 */
public record PlayerTownView(
        boolean available,
        boolean wilderness,
        String townId,
        String townName,
        boolean homeTown) {

    public PlayerTownView {
        townName = townName == null ? "" : townName;
    }

    /** 城邦信息不可用（provider 缺失或读取失败）：调用方必须按「不知道」降级，而不是当成「无城邦」。 */
    public static PlayerTownView unknown() {
        return new PlayerTownView(false, false, null, "", false);
    }

    /** 玩家站在所有城邦领地之外。 */
    public static PlayerTownView outsideTown() {
        return new PlayerTownView(true, true, null, "", false);
    }

    /** 玩家站在 {@code townId} 的领地上。 */
    public static PlayerTownView of(String townId, String townName, boolean homeTown) {
        return new PlayerTownView(true, false, Objects.requireNonNull(townId, "townId"), townName, homeTown);
    }

    /** 是否知道玩家此刻站在某座城邦的领地上。 */
    public boolean hasTown() {
        return available && !wilderness && townId != null && !townId.isBlank();
    }
}
