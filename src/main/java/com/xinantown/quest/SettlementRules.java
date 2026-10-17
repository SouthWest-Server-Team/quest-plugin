package com.xinantown.quest;

/**
 * 委托结算的<b>金额口径</b>（纯函数，不依赖 Bukkit / Vault）。
 *
 * <p>这些方法把原先散在 GUI、命令与调度器里的算式<b>原样搬出来</b>，行为未变。
 * 目的：① 让金额规则可被单测钉住——重做委托系统时能立刻看出「金额变了」；
 * ② 把「谁付谁、谁退谁」写成有名字的方法，替代散落的表达式。</p>
 *
 * <p>⚠️ 现状存在<b>不守恒</b>的路径（不是本次引入，也不判定为 bug）：
 * 两条「取消」路径让 {@code deposit + reward} 从经济中净消失；「驳回达上限终止」让
 * {@code reward} 净消失。精确数字由 {@code SettlementRulesCharacterizationTest} 钉住。</p>
 */
public final class SettlementRules {
    private SettlementRules() {
    }

    // —— 预付 ——

    /** 发布时一次性预扣：押金 + 报酬。 */
    public static double publisherPrepayment(double deposit, double reward) {
        return deposit + reward;
    }

    /** 接取时接取方另扣一份押金。 */
    public static double acceptorPrepayment(double deposit) {
        return deposit;
    }

    // —— 完成 ——

    /** 完成：发布方收回自己那份押金（报酬不回收）。 */
    public static double publisherRefundOnComplete(double deposit) {
        return deposit;
    }

    /** 完成：接取方拿回押金 + 报酬。 */
    public static double acceptorPayoutOnComplete(double deposit, double reward) {
        return deposit + reward;
    }

    // —— 终止（驳回累计达上限）——

    /** 终止：发布方收回押金。 */
    public static double publisherRefundOnTerminate(double deposit) {
        return deposit;
    }

    /** 终止：接取方收回押金。 */
    public static double acceptorRefundOnTerminate(double deposit) {
        return deposit;
    }

    // —— 取消 ——

    /** 取消：发布方违约罚金 = 报酬 × 系数（默认 0.5）。 */
    public static double publisherCancelPenalty(double reward, double penaltyFactor) {
        return reward * penaltyFactor;
    }

    /** 取消：接取方违约罚金 = 报酬 × 系数（默认 3.0）。 */
    public static double acceptorCancelPenalty(double reward, double penaltyFactor) {
        return reward * penaltyFactor;
    }

    /** 取消：取消方收回押金（发布方取消退发布方；接取方取消退接取方）。 */
    public static double cancellerRefund(double deposit) {
        return deposit;
    }

    /** 撤回（尚未被接取）：发布方收回押金。 */
    public static double publisherRefundOnWithdraw(double deposit) {
        return deposit;
    }

    // —— 超时 ——

    /** 超时（未接取）：发布方收回 押金 + 报酬。 */
    public static double publisherRefundOnExpireBeforeAccepted(double deposit, double reward) {
        return deposit + reward;
    }

    /** 超时（已接取）：接取方违约罚金 = 报酬 × 系数（默认 3.0）。 */
    public static double acceptorExpirePenalty(double reward, double penaltyFactor) {
        return reward * penaltyFactor;
    }

    /** 超时（已接取）：发布方收款 = 罚金 + 押金 + 报酬。 */
    public static double publisherReceiptsOnExpireAccepted(double penalty, double deposit, double reward) {
        return penalty + deposit + reward;
    }

    /** 超时（已接取）：接取方收回押金。 */
    public static double acceptorRefundOnExpireAccepted(double deposit) {
        return deposit;
    }

    // —— 净变化（把「守恒缺口」变成可断言的数字；**不**用于改变行为）——

    /** 发布方在「发布方取消」后的净变化。 */
    public static double netPublisherOnCancelByPublisher(double deposit, double reward, double factor) {
        return -publisherPrepayment(deposit, reward) - publisherCancelPenalty(reward, factor) + cancellerRefund(deposit);
    }

    /** 接取方在「发布方取消」后的净变化。 */
    public static double netAcceptorOnCancelByPublisher(double deposit, double reward, double factor) {
        return -acceptorPrepayment(deposit) + publisherCancelPenalty(reward, factor);
    }

    /** 接取方在「接取方取消」后的净变化。 */
    public static double netAcceptorOnCancelByAcceptor(double deposit, double reward, double factor) {
        return -acceptorPrepayment(deposit) - acceptorCancelPenalty(reward, factor) + cancellerRefund(deposit);
    }

    /** 发布方在「接取方取消」后的净变化。 */
    public static double netPublisherOnCancelByAcceptor(double deposit, double reward, double factor) {
        return -publisherPrepayment(deposit, reward) + acceptorCancelPenalty(reward, factor);
    }

    /** 发布方在「完成」后的净变化。 */
    public static double netPublisherOnComplete(double deposit, double reward) {
        return -publisherPrepayment(deposit, reward) + publisherRefundOnComplete(deposit);
    }

    /** 接取方在「完成」后的净变化。 */
    public static double netAcceptorOnComplete(double deposit, double reward) {
        return -acceptorPrepayment(deposit) + acceptorPayoutOnComplete(deposit, reward);
    }

    /** 发布方在「终止」后的净变化。 */
    public static double netPublisherOnTerminate(double deposit, double reward) {
        return -publisherPrepayment(deposit, reward) + publisherRefundOnTerminate(deposit);
    }

    /** 接取方在「终止」后的净变化。 */
    public static double netAcceptorOnTerminate(double deposit) {
        return -acceptorPrepayment(deposit) + acceptorRefundOnTerminate(deposit);
    }

    /** 发布方在「超时（已接取）」后的净变化。 */
    public static double netPublisherOnExpireAccepted(double deposit, double reward, double factor) {
        double penalty = acceptorExpirePenalty(reward, factor);
        return -publisherPrepayment(deposit, reward) + publisherReceiptsOnExpireAccepted(penalty, deposit, reward);
    }

    /** 接取方在「超时（已接取）」后的净变化。 */
    public static double netAcceptorOnExpireAccepted(double deposit, double reward, double factor) {
        return -acceptorPrepayment(deposit) - acceptorExpirePenalty(reward, factor) + acceptorRefundOnExpireAccepted(deposit);
    }
}
