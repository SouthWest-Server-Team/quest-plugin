package com.xinantown.quest.war;

import com.xinantown.api.war.WarRecord;
import com.xinantown.api.war.WarStateSnapshot;
import com.xinantown.api.war.WarStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CasusBelli 违约判定规则（纯函数：只用交互层只读契约的不可变 DTO 与 JDK，可直接单元测试）。
 *
 * <p>规则与改造前保持一致：
 * <ul>
 *   <li>只有 {@link WarStatus#ACTIVE} 的战争才会产生违约（PENDING / ENDED 不算）；</li>
 *   <li>委托的发布方城镇与接取方城镇必须处在这场战争的对立两侧；</li>
 *   <li>违约只记给<b>宣战方</b>（{@link WarRecord#attackerTown()}）。</li>
 * </ul>
 *
 * <p>去重标识从「城镇名」换成了交互层契约给出的稳定标识：{@code questId + ":" + warId}。
 * 原实现的注释写明意图是「不重复惩罚同一个 quest/war 组合」，但用城镇名做键无法区分同一对城镇之间
 * 先后爆发的两场战争；{@code warId} 由 War 模块自己拥有、在战争生命周期内稳定，
 * 因此现在才是字面意义上的「同一个 quest/war 组合只罚一次」。
 */
public final class WarViolationDetector {

    /** 一条已接取的城邦委托，收敛成规则真正需要的两个城镇名。 */
    public record Candidate(UUID questId, String questTitle, String publisherTown, String acceptorTown) {
        public Candidate {
            Objects.requireNonNull(questId, "questId");
            Objects.requireNonNull(publisherTown, "publisherTown");
            Objects.requireNonNull(acceptorTown, "acceptorTown");
        }
    }

    /** 一次需要记账的违约：哪场战争（稳定 warId）与哪条委托冲突，罚哪个城镇。 */
    public record Violation(UUID questId, String questTitle, String warId, String attackerTown) {
        public Violation {
            Objects.requireNonNull(questId, "questId");
            Objects.requireNonNull(warId, "warId");
            Objects.requireNonNull(attackerTown, "attackerTown");
        }
    }

    /** 已经记过账的 quest/war 组合，避免同一场战争被反复惩罚。 */
    private final Set<String> punishedWarQuestPairs = new HashSet<>();

    /**
     * 判定本轮需要记账的违约。
     *
     * @param candidates 已接取的城邦委托（发布方/接取方城镇名已解析）
     * @param snapshot   交互层给出的战争快照，可能为 {@link WarStateSnapshot#empty()}
     * @return 本轮首次判定出的违约，可能为空；同一 quest/war 组合只会返回一次
     */
    public List<Violation> detect(List<Candidate> candidates, WarStateSnapshot snapshot) {
        if (candidates == null || candidates.isEmpty()
                || snapshot == null || snapshot.wars().isEmpty()) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            for (WarRecord war : snapshot.wars()) {
                if (war == null || war.status() != WarStatus.ACTIVE) {
                    continue;
                }
                // 城镇名的大小写不敏感比较由契约自己负责（WarRecord.involves）。
                if (!war.involves(candidate.publisherTown())
                        || !war.involves(candidate.acceptorTown())) {
                    continue;
                }
                if (!punishedWarQuestPairs.add(candidate.questId() + ":" + war.warId())) {
                    continue;
                }
                violations.add(new Violation(candidate.questId(), candidate.questTitle(),
                        war.warId(), war.attackerTown()));
            }
        }
        return violations;
    }

    /** 供诊断用：已记账的组合数量。 */
    public int punishedPairCount() {
        return punishedWarQuestPairs.size();
    }
}
