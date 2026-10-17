package com.xinantown.quest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>特征化测试（characterization）</b>：只记录**当前**结算金额口径，不评判对错。
 *
 * <p>基准数据：{@code deposit = 100}、{@code reward = 500}、发布方取消罚金系数 {@code 0.5}、
 * 接取方（及超时）罚金系数 {@code 3.0}（与 {@code config.yml} 默认值一致）。</p>
 *
 * <p>⚠️ 下面标注「现状不守恒」的用例是**当前行为**：重做委托系统（KB `ADR-0012`）时必须
 * 显式决定要不要保持，不能悄悄改掉。</p>
 */
class SettlementRulesCharacterizationTest {

    private static final double DEPOSIT = 100.0;
    private static final double REWARD = 500.0;
    private static final double PUB_FACTOR = 0.5;
    private static final double ACC_FACTOR = 3.0;
    private static final double EPS = 1e-9;

    @Test
    @DisplayName("预付：发布方 deposit+reward，接取方 deposit")
    void prepayments() {
        assertEquals(600.0, SettlementRules.publisherPrepayment(DEPOSIT, REWARD), EPS);
        assertEquals(100.0, SettlementRules.acceptorPrepayment(DEPOSIT), EPS);
    }

    @Test
    @DisplayName("完成：双方各自回本 + 接取方拿报酬 ⇒ 守恒")
    void completeConservesMoney() {
        assertEquals(100.0, SettlementRules.publisherRefundOnComplete(DEPOSIT), EPS);
        assertEquals(600.0, SettlementRules.acceptorPayoutOnComplete(DEPOSIT, REWARD), EPS);

        double publisher = SettlementRules.netPublisherOnComplete(DEPOSIT, REWARD);
        double acceptor = SettlementRules.netAcceptorOnComplete(DEPOSIT, REWARD);
        assertEquals(-500.0, publisher, EPS);   // 发布方净付出 = 报酬
        assertEquals(500.0, acceptor, EPS);     // 接取方净得到 = 报酬
        assertEquals(0.0, publisher + acceptor, EPS); // 守恒
    }

    @Test
    @DisplayName("终止（驳回达上限）：双方退押金，报酬不退 ⇒ 现状不守恒：净消失 reward")
    void terminateLosesReward() {
        assertEquals(100.0, SettlementRules.publisherRefundOnTerminate(DEPOSIT), EPS);
        assertEquals(100.0, SettlementRules.acceptorRefundOnTerminate(DEPOSIT), EPS);

        double publisher = SettlementRules.netPublisherOnTerminate(DEPOSIT, REWARD);
        double acceptor = SettlementRules.netAcceptorOnTerminate(DEPOSIT);
        assertEquals(-500.0, publisher, EPS);
        assertEquals(0.0, acceptor, EPS);      // 接取方不亏
        assertEquals(-500.0, publisher + acceptor, EPS); // 现况：reward 净消失
    }

    @Test
    @DisplayName("发布方取消：接取方拿罚金但押金不退 ⇒ 现状不守恒：净消失 deposit+reward")
    void publisherCancelLosesDepositAndReward() {
        assertEquals(250.0, SettlementRules.publisherCancelPenalty(REWARD, PUB_FACTOR), EPS);
        assertEquals(100.0, SettlementRules.cancellerRefund(DEPOSIT), EPS);

        double publisher = SettlementRules.netPublisherOnCancelByPublisher(DEPOSIT, REWARD, PUB_FACTOR);
        double acceptor = SettlementRules.netAcceptorOnCancelByPublisher(DEPOSIT, REWARD, PUB_FACTOR);
        assertEquals(-750.0, publisher, EPS);  // 预付 600 + 罚金 250 - 退回押金 100
        assertEquals(150.0, acceptor, EPS);    // 拿罚金 250 - 自己的押金 100
        assertEquals(-600.0, publisher + acceptor, EPS); // 现况：deposit+reward 净消失
    }

    @Test
    @DisplayName("接取方取消：接取方被罚 3×reward（押金退还），发布方拿罚金 ⇒ 现状不守恒：净消失 deposit+reward")
    void acceptorCancelLosesDepositAndReward() {
        assertEquals(1500.0, SettlementRules.acceptorCancelPenalty(REWARD, ACC_FACTOR), EPS);

        double acceptor = SettlementRules.netAcceptorOnCancelByAcceptor(DEPOSIT, REWARD, ACC_FACTOR);
        double publisher = SettlementRules.netPublisherOnCancelByAcceptor(DEPOSIT, REWARD, ACC_FACTOR);
        assertEquals(-1500.0, acceptor, EPS);
        assertEquals(900.0, publisher, EPS);   // -600 + 1500
        assertEquals(-600.0, publisher + acceptor, EPS); // 现况：deposit+reward 净消失
    }

    @Test
    @DisplayName("撤回（未被接取）：只退押金 ⇒ 发布方净亏 reward")
    void withdrawBeforeAcceptedLosesReward() {
        assertEquals(100.0, SettlementRules.publisherRefundOnWithdraw(DEPOSIT), EPS);
        assertEquals(-500.0,
                -SettlementRules.publisherPrepayment(DEPOSIT, REWARD) + SettlementRules.publisherRefundOnWithdraw(DEPOSIT), EPS);
    }

    @Test
    @DisplayName("超时（未被接取）：退 deposit+reward ⇒ 双方都回本")
    void expireBeforeAcceptedRefundsBoth() {
        assertEquals(600.0, SettlementRules.publisherRefundOnExpireBeforeAccepted(DEPOSIT, REWARD), EPS);
        assertEquals(0.0,
                -SettlementRules.publisherPrepayment(DEPOSIT, REWARD)
                        + SettlementRules.publisherRefundOnExpireBeforeAccepted(DEPOSIT, REWARD), EPS);
    }

    @Test
    @DisplayName("超时（已接取）：接取方被扣 3×reward 给发布方 ⇒ 守恒（罚金是真扣出来的）")
    void expireAcceptedConservesMoney() {
        assertEquals(1500.0, SettlementRules.acceptorExpirePenalty(REWARD, ACC_FACTOR), EPS);
        assertEquals(2100.0, SettlementRules.publisherReceiptsOnExpireAccepted(1500.0, DEPOSIT, REWARD), EPS);
        assertEquals(100.0, SettlementRules.acceptorRefundOnExpireAccepted(DEPOSIT), EPS);

        double publisher = SettlementRules.netPublisherOnExpireAccepted(DEPOSIT, REWARD, ACC_FACTOR);
        double acceptor = SettlementRules.netAcceptorOnExpireAccepted(DEPOSIT, REWARD, ACC_FACTOR);
        assertEquals(1500.0, publisher, EPS);  // -600 + 2100
        assertEquals(-1500.0, acceptor, EPS);  // -100 - 1500 + 100
        assertEquals(0.0, publisher + acceptor, EPS); // 守恒
    }
}
