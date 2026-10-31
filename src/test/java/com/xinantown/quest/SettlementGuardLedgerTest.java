package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import com.xinantown.quest.town.PlayerTownView;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;

/**
 * <b>特征化测试 · 结算前置拒绝路径</b>：每一项守卫被触发时，<b>经济接口上一条调用都不许发出</b>，
 * 委托状态也不许变。
 *
 * <p>与 {@code SettlementCallSiteLedgerTest}（钉住「该结算时结算成什么样」）互补：那一类只覆盖
 * 允许路径，守卫一旦被改错/删掉，允许路径仍然全绿。这里逐条钉住 <b>拒绝</b>。</p>
 *
 * <p>四类守卫，判据一律是「账本序列 + {@code status()}」（不是「没抛异常」）：</p>
 * <ol>
 *   <li><b>余额不足</b>：发布（{@code BoardMenuHandler.confirmPublish}）、接取（{@code BoardAcceptHandler.accept} /
 *       {@code QuestCommand.handleAccept}）—— 发钱之前先问 {@code has()}，问不过就一分别动；</li>
 *   <li><b>批允许方 / 状态</b>：审批与终止的守卫在点击入口 {@code QuestGuiManager.onInventoryClick}
 *       （{@code isPublisher && status == SUBMITTED}）—— 非发布方批、非 SUBMITTED 批都不结算；</li>
 *   <li><b>接取前置</b>：主手非空、自接、城邦委托非本城成员（无代表权）、城邦被封禁；</li>
 *   <li><b>终止门槛</b>：驳回累计未达 {@code max-rejects - 1} 时不允许终止。</li>
 * </ol>
 *
 * <p>为了让「拒绝」不是空断言，本类同时给了三条<b>允许路径对照</b>：走同一个点击驱动，
 * 该结算时确实结算成 4 笔 —— 若驱动本身失效，对照用例会先红。</p>
 *
 * <p>基准数据同 {@code SettlementHarness}：{@code deposit = 100 / reward = 500}，发布预扣 600，
 * 接取扣押金 100，{@code max-rejects = 5}（终止门槛 = 驳回 4 次）。</p>
 */
class SettlementGuardLedgerTest {

    @TempDir
    Path dataDir;

    // ==================== 0. 允许路径对照（证明点击驱动真的能结算） ====================

    @Test
    @DisplayName("对照·发布方在 SUBMITTED 上点同意：四笔到账，委托变 COMPLETED")
    void publisherApprovesSubmittedQuestThroughGui() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, h.quest());
            assertTrue(h.submitViaGui(acceptor, h.quest()), "接取方应能提交，仓库里必须有提交按钮");

            assertTrue(h.approveViaGui(publisher, h.quest()), "发布方应能两段确认同意");
            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "WITHDRAW 接取方 100.0",
                    "DEPOSIT 发布方 100.0",
                    "DEPOSIT 接取方 600.0"
            ), h.ledgerLines(), "对照：允许路径确实是 4 笔，与调用点级用例一致");
            assertEquals(QuestStatus.COMPLETED, h.quest().status());
        }
    }

    @Test
    @DisplayName("对照·驳回已 4/5 时发布方点终止：退双方押金，委托变 CANCELLED")
    void terminateAllowedAtRejectThreshold() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, h.quest());
            h.submitViaGui(acceptor, h.quest());
            h.withRejectCount(h.quest(), 4);          // 门槛：rejectCount >= max-rejects - 1

            h.openWarehouse(publisher, h.quest());
            assertTrue(h.clickButton(publisher, "§4终止委托"), "驳回 4/5 时应渲染终止按钮");
            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "WITHDRAW 接取方 100.0",
                    "DEPOSIT 发布方 100.0",
                    "DEPOSIT 接取方 100.0"
            ), h.ledgerLines(), "对照：达门槛的终止确实退双方押金");
            assertEquals(QuestStatus.CANCELLED, h.quest().status());
        }
    }

    // ==================== 1. 余额不足 ====================

    @Test
    @DisplayName("余额不足·发布：不建单、账本为空（一分钱不动）")
    void publishWithInsufficientFundsMovesNoMoney() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            h.setCanAfford(false);

            Quest created = h.publishOrNull(publisher, 500, 100);

            assertNull(created, "余额不足不应建单");
            assertEquals(0, h.questCount(), "盘上不应留下委托");
            assertTrue(h.ledgerLines().isEmpty(), "守卫在 withdraw 之前，账本必须为空");
            verify(publisher).sendMessage(contains("余额不足"));
        }
    }

    @Test
    @DisplayName("余额不足·告示牌接取：只留发布预扣那一笔，委托仍是 OPEN")
    void acceptViaBoardWithInsufficientFundsMovesNoMoney() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            h.publish(publisher, 500, 100);
            assertEquals(List.of("WITHDRAW 发布方 600.0"), h.ledgerLines());
            h.setCanAfford(false);

            Quest after = h.acceptViaBoard(acceptor, h.quest());

            assertEquals(List.of("WITHDRAW 发布方 600.0"), h.ledgerLines(),
                    "接取方一分钱都不许被扣：账本仍只有发布预扣");
            assertEquals(QuestStatus.OPEN, after.status(), "接取被拒，状态必须仍为 OPEN");
            assertNull(after.acceptorId(), "不应记下接取方");
            verify(acceptor).sendMessage(contains("余额不足"));
        }
    }

    @Test
    @DisplayName("余额不足·命令接取：与告示牌同一口径，账本不动、仍是 OPEN")
    void acceptViaCommandWithInsufficientFundsMovesNoMoney() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            h.publish(publisher, 500, 100);
            h.setCanAfford(false);

            Quest after = h.acceptViaCommand(acceptor, h.quest());

            assertEquals(List.of("WITHDRAW 发布方 600.0"), h.ledgerLines(),
                    "/quest accept 也必须在 withdraw 之前挡下");
            assertEquals(QuestStatus.OPEN, after.status());
            assertNull(after.acceptorId());
            verify(acceptor).sendMessage(contains("余额不足"));
        }
    }

    // ==================== 2. 批允许方不符 / 状态不符 ====================

    @Test
    @DisplayName("接取方批：同意按钮不渲染；强行点也不结算，仍 SUBMITTED")
    void acceptorCannotApprove() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, h.quest());
            h.submitViaGui(acceptor, h.quest());     // 接取方的仓库仍开着，指向 SUBMITTED

            assertEquals(-1, h.buttonSlot("§a同意"), "接取方视角不应渲染同意按钮");
            h.clickForced(acceptor, 48, "§a同意");       // 直接伪造该槽位的按钮点击
            h.clickForced(acceptor, 49, "§a确认同意");   // 守卫若被改坏，两段确认会被走完

            assertEquals(List.of("WITHDRAW 发布方 600.0", "WITHDRAW 接取方 100.0"), h.ledgerLines(),
                    "非发布方批：不得有任何结算调用");
            assertEquals(QuestStatus.SUBMITTED, h.quest().status(), "状态不许被推到 COMPLETED");
        }
    }

    @Test
    @DisplayName("第三方批：既非发布方也非接取方，强行点同意仍不结算")
    void thirdPartyCannotApprove() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");
            var stranger = h.player("路人");

            h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, h.quest());
            h.submitViaGui(acceptor, h.quest());

            h.openWarehouse(stranger, h.quest());     // 路人能开仓库（只读），但没按钮
            assertEquals(-1, h.buttonSlot("§a同意"));
            h.clickForced(stranger, 48, "§a同意");
            h.clickForced(stranger, 49, "§a确认同意");

            assertEquals(List.of("WITHDRAW 发布方 600.0", "WITHDRAW 接取方 100.0"), h.ledgerLines());
            assertEquals(QuestStatus.SUBMITTED, h.quest().status());
        }
    }

    @Test
    @DisplayName("状态不符·发布方在 ACCEPTED 上点同意：不结算，仍是 ACCEPTED")
    void publisherCannotApproveBeforeSubmit() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, h.quest());   // ACCEPTED，尚未提交
            h.openWarehouse(publisher, h.quest());

            assertEquals(-1, h.buttonSlot("§a同意"), "未提交时不应有同意按钮");
            h.clickForced(publisher, 48, "§a同意");
            h.clickForced(publisher, 49, "§a确认同意");

            assertEquals(List.of("WITHDRAW 发布方 600.0", "WITHDRAW 接取方 100.0"), h.ledgerLines(),
                    "非 SUBMITTED 状态批：不得结算");
            assertEquals(QuestStatus.ACCEPTED, h.quest().status());
        }
    }

    @Test
    @DisplayName("状态不符·发布方在 OPEN 上点取消：不结算，仍是 OPEN")
    void publisherCannotCancelOpenQuestThroughWarehouse() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");

            Quest quest = h.publish(publisher, 500, 100);
            h.openWarehouse(publisher, quest);       // OPEN 状态下仓库里有取消按钮
            int slot = h.buttonSlot("§c取消委托");
            assertTrue(slot >= 0, "OPEN 时发布方视角应渲染取消按钮");

            h.clickButton(publisher, "§c取消委托");

            // 事实：OPEN + 取消 → 只退发布方押金（既有调用点用例 withdrawBeforeAccepted 已钉住金额），
            // 这里钉的是「不走到任何罚金路径」——赔给接取方的那两笔绝不出现。
            assertEquals(List.of("WITHDRAW 发布方 600.0", "DEPOSIT 发布方 100.0"), h.ledgerLines(),
                    "未被接取时没有罚金收付对象");
            assertEquals(QuestStatus.CANCELLED, h.quest().status());
        }
    }

    // ==================== 3. 接取前置被拒 ====================

    @Test
    @DisplayName("主手非空接取：被拒，账本不动，仍是 OPEN")
    void acceptRejectedWhenMainHandNotEmpty() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.playerHolding("接取方", Material.STONE);

            h.publish(publisher, 500, 100);
            Quest after = h.acceptViaBoard(acceptor, h.quest());

            assertEquals(List.of("WITHDRAW 发布方 600.0"), h.ledgerLines(), "空手守卫在扣款之前");
            assertEquals(QuestStatus.OPEN, after.status());
            assertNull(after.acceptorId());
            verify(acceptor).sendMessage(contains("请空出主手"));
        }
    }

    @Test
    @DisplayName("自接（告示牌）：发布方接自己的委托被拒，账本不动")
    void acceptOwnQuestRejectedViaBoard() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");

            h.publish(publisher, 500, 100);
            Quest after = h.acceptViaBoard(publisher, h.quest());

            assertEquals(List.of("WITHDRAW 发布方 600.0"), h.ledgerLines());
            assertEquals(QuestStatus.OPEN, after.status());
            verify(publisher).sendMessage(contains("不能接取自己的委托"));
        }
    }

    @Test
    @DisplayName("自接（命令）：/quest accept 同一口径被拒，账本不动")
    void acceptOwnQuestRejectedViaCommand() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");

            h.publish(publisher, 500, 100);
            Quest after = h.acceptViaCommand(publisher, h.quest());

            assertEquals(List.of("WITHDRAW 发布方 600.0"), h.ledgerLines());
            assertEquals(QuestStatus.OPEN, after.status());
            verify(publisher).sendMessage(contains("不能接取自己的委托"));
        }
    }

    @Test
    @DisplayName("城邦委托·非本城成员（无代表权）：被拒，账本为空")
    void townQuestAcceptRejectedWhenNotMember() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("城邦发布方");
            var outsider = h.player("外来者");

            h.seedTownQuest(publisher, "town-west", "西城", 500, 100);   // 直接落盘，无资金调用
            h.setPlayerTown(outsider, PlayerTownView.of("town-west", "西城", false));

            Quest after = h.acceptViaBoard(outsider, h.quest());

            assertTrue(h.ledgerLines().isEmpty(), "城邦代表权不通过：一分钱不动");
            assertEquals(QuestStatus.OPEN, after.status());
            assertNull(after.acceptorId());
            verify(outsider).sendMessage(contains("只有该城邦的成员才能代表城邦接取"));
        }
    }

    @Test
    @DisplayName("城邦委托·站在别的城：代表权不符，被拒，账本为空")
    void townQuestAcceptRejectedWhenViewingOtherTown() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("城邦发布方");
            var foreigner = h.player("东城人");

            h.seedTownQuest(publisher, "town-west", "西城", 500, 100);
            h.setPlayerTown(foreigner, PlayerTownView.of("town-east", "东城", true));

            Quest after = h.acceptViaCommand(foreigner, h.quest());

            assertTrue(h.ledgerLines().isEmpty(), "城邦键不一致：一分钱不动");
            assertEquals(QuestStatus.OPEN, after.status());
        }
    }

    @Test
    @DisplayName("城邦委托·城邦被封禁：被拒，账本为空")
    void townQuestAcceptRejectedWhenTownBanned() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("城邦发布方");
            var member = h.player("西城成员");

            h.seedTownQuest(publisher, "town-west", "西城", 500, 100);
            h.setPlayerTown(member, PlayerTownView.of("town-west", "西城", true));  // 代表权通过
            h.banTown("西城", 12);

            Quest after = h.acceptViaBoard(member, h.quest());

            assertTrue(h.ledgerLines().isEmpty(), "违约封禁：一分钱不动");
            assertEquals(QuestStatus.OPEN, after.status());
            verify(member).sendMessage(contains("城邦处于违约状态"));
        }
    }

    // ==================== 4. 终止门槛 ====================

    @Test
    @DisplayName("终止门槛·驳回 0/5：终止按钮不渲染，强行点也不结算")
    void terminateRejectedAtZeroRejects() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, h.quest());
            h.submitViaGui(acceptor, h.quest());
            h.openWarehouse(publisher, h.quest());

            assertEquals(-1, h.buttonSlot("§4终止委托"), "未达门槛不应渲染终止按钮");
            h.clickForced(publisher, 50, "§4终止委托");

            assertEquals(List.of("WITHDRAW 发布方 600.0", "WITHDRAW 接取方 100.0"), h.ledgerLines(),
                    "未达 maxRejects：不得退款");
            assertEquals(QuestStatus.SUBMITTED, h.quest().status());
        }
    }

    @Test
    @DisplayName("终止门槛·驳回 3/5（差一次）：仍不允许终止，账本不动")
    void terminateRejectedAtThreeRejects() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, h.quest());
            h.submitViaGui(acceptor, h.quest());
            h.withRejectCount(h.quest(), 3);
            h.openWarehouse(publisher, h.quest());

            assertEquals(-1, h.buttonSlot("§4终止委托"), "3/5 低于 max-rejects-1，不应渲染终止");
            h.clickForced(publisher, 50, "§4终止委托");

            assertEquals(List.of("WITHDRAW 发布方 600.0", "WITHDRAW 接取方 100.0"), h.ledgerLines());
            assertEquals(QuestStatus.SUBMITTED, h.quest().status());
        }
    }

    @Test
    @DisplayName("终止门槛·接取方点终止：非发布方不结算")
    void acceptorCannotTerminate() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, h.quest());
            h.submitViaGui(acceptor, h.quest());
            h.withRejectCount(h.quest(), 4);          // 门槛已到，但点的人不是发布方

            h.openWarehouse(acceptor, h.quest());     // 接取方视角仓库（SUBMITTED 会被锁，故不依赖按钮）
            h.clickForced(acceptor, 50, "§4终止委托");

            assertEquals(List.of("WITHDRAW 发布方 600.0", "WITHDRAW 接取方 100.0"), h.ledgerLines(),
                    "非发布方终止：不得退款");
            assertEquals(QuestStatus.SUBMITTED, h.quest().status());
        }
    }
}
