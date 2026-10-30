package com.xinantown.quest.town;

import com.xinantown.api.provider.PlayerTownProvider;
import com.xinantown.api.provider.ProviderRequirement;

import java.util.List;
import java.util.Objects;

/**
 * 交互层「玩家脚下城邦」provider 的选取规则（纯函数，只依赖 xinantown-api 与 JDK，可直接单元测试）。
 *
 * <p>与 {@code WarProviderSelector} 同款：先用 {@code ProviderCompatibility} 按 provider id /
 * API 版本 / 能力过滤兼容性，再取 Bukkit 服务优先级最高者；高优先级但不兼容的注册不会遮蔽
 * 低优先级的兼容注册。真正的注册枚举留在 {@link TownQueryBridge} 里。
 */
public final class TownProviderSelector {

    /**
     * 交互层给出的一条注册。
     *
     * @param provider        provider 本体
     * @param priorityOrdinal Bukkit {@code ServicePriority} 的序号，越大优先级越高
     */
    public record Candidate(PlayerTownProvider provider, int priorityOrdinal) {
        public Candidate {
            Objects.requireNonNull(provider, "provider");
        }
    }

    private TownProviderSelector() {
    }

    /**
     * 选出一个兼容的玩家城邦 provider。
     *
     * @return 优先级最高的兼容 provider；没有任何兼容注册时返回 {@code null}
     */
    public static PlayerTownProvider select(ProviderRequirement requirement, List<Candidate> candidates) {
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(candidates, "candidates");

        PlayerTownProvider selected = null;
        int selectedPriority = Integer.MIN_VALUE;
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.provider() == null) {
                continue;
            }
            // metadata() 抛异常的 provider 视为不兼容（ProviderCompatibility 内部已兜住）。
            if (!com.xinantown.api.provider.ProviderCompatibility.isCompatible(
                    candidate.provider(), requirement)) {
                continue;
            }
            if (selected == null || candidate.priorityOrdinal() > selectedPriority) {
                selected = candidate.provider();
                selectedPriority = candidate.priorityOrdinal();
            }
        }
        return selected;
    }
}
