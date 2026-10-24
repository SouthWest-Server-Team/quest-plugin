package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>特征化测试 · 调用点级</b>：把 18 处资金调用点逐个钉住 —— 谁付、付多少、按什么顺序。
 *
 * <p>基准 {@code deposit = 100 / reward = 500}（系数 0.5 / 3.0，与 config.yml 默认一致）。
 * 行号对应基线 {@code debug @ d3e7da0}。</p>
 *
 * <p>为什么要「调用点级」而不再是纯函数级：{@code SettlementRules} 只钉住了算式，
 * 谁被调、调几次、顺序、收付对象都能悄悄改错而算式测试全绿。这里断言的是
 * <b>经济接口上真正发出的调用序列</b>（账本），改一处就红。</p>
 *
 * <pre>
 *  ① BoardMenuHandler.java:183      withdraw(发布方, deposit+reward)
 *  ② BoardAcceptHandler.java:141    withdraw(接取方, deposit)
 *  ③ QuestCommand.java:107          withdraw(接取方, deposit)
 *  ④ QuestGuiManager.java:305       deposit(发布方, deposit)             完成
 *  ⑤ QuestGuiManager.java:306       deposit(接取方, deposit+reward)      完成
 *  ⑥ QuestGuiManager.java:354       deposit(发布方, deposit)             终止
 *  ⑦ QuestGuiManager.java:355       deposit(接取方, deposit)             终止
 *  ⑧ QuestGuiManager.java:379       withdraw(发布方, reward×0.5)         发布方取消·罚金
 *  ⑨ QuestGuiManager.java:380       deposit(接取方, reward×0.5)          发布方取消·罚金去向
 *  ⑩ QuestGuiManager.java:381       deposit(发布方, deposit)             发布方取消·退押金
 *  ⑪ QuestGuiManager.java:390       withdraw(接取方, reward×3.0)         接取方取消·罚金
 *  ⑫ QuestGuiManager.java:391       deposit(发布方, reward×3.0)          接取方取消·罚金去向
 *  ⑬ QuestGuiManager.java:392       deposit(接取方, deposit)             接取方取消·退押金
 *  ⑭ QuestGuiManager.java:397       deposit(发布方, deposit)             撤回（未被接取）
 *  ⑮ QuestScheduler.java:56         deposit(发布方, deposit+reward)      超时·未被接取
 *  ⑯ QuestScheduler.java:67         withdraw(接取方, reward×3.0)         超时·已接取
 *  ⑰ QuestScheduler.java:68         deposit(发布方, reward×3.0+deposit+reward)
 *  ⑱ QuestScheduler.java:69         deposit(接取方, deposit)             超时·已接取·退押金
 * </pre>
 *
 * <p>⚠️ 下面出现的「接取方押金不退」「报酬不退」都是<b>当前实现的事实</b>，不是本次判定的对错；
 * 重做委托（KB {@code ADR-0012}）时必须显式决定。</p>
 */
class SettlementCallSiteLedgerTest {

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("完成（告示牌接取）：预扣 600 → 接取扣押金 100 → 退发布方 100 + 给接取方 600")
    void completeViaBoardAccept() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            var quest = h.publish(publisher, 500, 100);          // ①
            h.acceptViaBoard(acceptor, quest);                    // ②
            Quest done = h.approve(publisher, h.quest());         // ④⑤

            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "WITHDRAW 接取方 100.0",
                    "DEPOSIT 发布方 100.0",
                    "DEPOSIT 接取方 600.0"
            ), h.ledgerLines(), "完成的四笔资金调用（含顺序）与现版一致");
            assertEquals(QuestStatus.COMPLETED, done.status());
            assertEquals(4, h.ledger().size(), "完成路径一共只动 4 次钱");
        }
    }

    @Test
    @DisplayName("完成（命令接取）：/quest accept 与告示牌走同一口径 —— 同样只扣接取方押金 100")
    void completeViaCommandAccept() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            var quest = h.publish(publisher, 500, 100);           // ①
            h.acceptViaCommand(acceptor, quest);                  // ③
            h.approve(publisher, h.quest());                      // ④⑤

            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "WITHDRAW 接取方 100.0",   // ③ QuestCommand.java:107 —— 与 ② 金额/对象完全一致
                    "DEPOSIT 发布方 100.0",
                    "DEPOSIT 接取方 600.0"
            ), h.ledgerLines(), "两条接取入口（告示牌/命令）必须扣同一笔押金");
        }
    }

    @Test
    @DisplayName("终止（驳回达上限）：双方各退自己那份押金 100，报酬 500 不退")
    void terminateRefundsDepositsOnly() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            var quest = h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, quest);
            h.terminate(publisher, h.quest());                    // ⑥⑦

            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "WITHDRAW 接取方 100.0",
                    "DEPOSIT 发布方 100.0",
                    "DEPOSIT 接取方 100.0"     // 不是 deposit+reward —— 报酬凭空消失（现版事实）
            ), h.ledgerLines());
        }
    }

    @Test
    @DisplayName("发布方取消：扣罚金 250（reward×0.5）给接取方，只退发布方押金；接取方押金不退")
    void publisherCancel() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            var quest = h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, quest);
            h.cancel(publisher, h.quest());                       // ⑧⑨⑩

            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "WITHDRAW 接取方 100.0",
                    "WITHDRAW 发布方 250.0",   // ⑧ reward 500 × 0.5
                    "DEPOSIT 接取方 250.0",    // ⑨ 罚金打给接取方
                    "DEPOSIT 发布方 100.0"     // ⑩ 只退取消方（发布方）自己的押金
            ), h.ledgerLines());
        }
    }

    @Test
    @DisplayName("接取方取消：扣罚金 1500（reward×3.0）给发布方，只退接取方押金；发布方押金不退")
    void acceptorCancel() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            var quest = h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, quest);
            h.cancel(acceptor, h.quest());                        // ⑪⑫⑬

            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "WITHDRAW 接取方 100.0",
                    "WITHDRAW 接取方 1500.0",  // ⑪ reward 500 × 3.0
                    "DEPOSIT 发布方 1500.0",   // ⑫
                    "DEPOSIT 接取方 100.0"     // ⑬ 只退取消方（接取方）自己的押金
            ), h.ledgerLines());
        }
    }

    @Test
    @DisplayName("撤回（未被接取）：只退发布方押金 100 —— 发布时预扣的报酬 500 不退")
    void withdrawBeforeAccepted() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");

            var quest = h.publish(publisher, 500, 100);
            assertEquals(QuestStatus.OPEN, quest.status());
            h.cancel(publisher, quest);                           // ⑭

            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "DEPOSIT 发布方 100.0"     // ⑭ 只有押金回来
            ), h.ledgerLines());
        }
    }

    @Test
    @DisplayName("超时（未被接取）：退发布方 deposit+reward = 600，双方回本")
    void expireBeforeAccepted() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");

            var quest = h.publish(publisher, 500, 100);
            h.expireAcceptDeadline(quest);
            h.expireTick();                                       // ⑮

            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "DEPOSIT 发布方 600.0"     // ⑮ 押金+报酬一起退
            ), h.ledgerLines());
        }
    }

    @Test
    @DisplayName("超时（已接取）：扣接取方 1500 给发布方，发布方另收 2100，接取方押金退回")
    void expireAccepted() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            var quest = h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, quest);
            h.expireCompleteDeadline(h.quest());
            h.expireTick();                                       // ⑯⑰⑱

            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "WITHDRAW 接取方 100.0",
                    "WITHDRAW 接取方 1500.0",  // ⑯ 违约罚金 = reward × 3.0
                    "DEPOSIT 发布方 2100.0",   // ⑰ 1500 + 100 + 500（罚金先扣出来再给，不凭空产生）
                    "DEPOSIT 接取方 100.0"     // ⑱ 押金退回
            ), h.ledgerLines());
        }
    }

    @Test
    @DisplayName("超时（已接取·个人接取方）：不进违约名单（只有城邦才记账）")
    void expireAcceptedPersonalAcceptorDoesNotRegisterViolation() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            var quest = h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, quest);
            h.expireCompleteDeadline(h.quest());
            h.expireTick();

            org.mockito.Mockito.verify(h.violations(), org.mockito.Mockito.never())
                    .addViolation(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test
    @DisplayName("罚金系数真的来自 config：发布方系数 0.4 ⇒ 罚金 200（不是写死的 0.5）")
    void penaltyFactorComesFromConfig() {
        try (SettlementHarness h = new SettlementHarness(dataDir)) {
            h.setConfig("cancel-penalty-publisher", 0.4);
            var publisher = h.player("发布方");
            var acceptor = h.player("接取方");

            var quest = h.publish(publisher, 500, 100);
            h.acceptViaBoard(acceptor, quest);
            h.cancel(publisher, h.quest());

            assertEquals(List.of(
                    "WITHDRAW 发布方 600.0",
                    "WITHDRAW 接取方 100.0",
                    "WITHDRAW 发布方 200.0",   // 500 × 0.4
                    "DEPOSIT 接取方 200.0",
                    "DEPOSIT 发布方 100.0"
            ), h.ledgerLines());
        }
    }
}
