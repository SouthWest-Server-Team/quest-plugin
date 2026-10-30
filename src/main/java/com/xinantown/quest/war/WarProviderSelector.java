package com.xinantown.quest.war;

import com.xinantown.api.provider.ProviderCompatibility;
import com.xinantown.api.provider.ProviderRequirement;
import com.xinantown.api.provider.WarInfoProvider;

import java.util.List;
import java.util.Objects;

/**
 * 交互层战争 provider 的选取规则（纯函数，只依赖 xinantown-api 与 JDK，可直接单元测试）。
 *
 * <p>规则与交互层运行时一致：先用 {@link ProviderCompatibility} 按 provider id / API 版本 / 能力过滤，
 * 再取 Bukkit 服务优先级最高者；高优先级但不兼容的注册不会遮蔽低优先级的兼容注册。
 *
 * <p>本类不依赖 Bukkit，因此「谁被选中」这件事可以在没有服务端的情况下被测试；
 * 真正的注册枚举留在 {@link WarInfoBridge} 里。
 */
public final class WarProviderSelector {

    /**
     * 交互层给出的一条注册。
     *
     * @param provider        provider 本体
     * @param priorityOrdinal Bukkit {@code ServicePriority} 的序号，越大优先级越高
     */
    public record Candidate(WarInfoProvider provider, int priorityOrdinal) {
        public Candidate {
            Objects.requireNonNull(provider, "provider");
        }
    }

    private WarProviderSelector() {
    }

    /**
     * 选出一个兼容的战争信息 provider。
     *
     * @return 优先级最高的兼容 provider；没有任何兼容注册时返回 {@code null}
     */
    public static WarInfoProvider select(ProviderRequirement requirement, List<Candidate> candidates) {
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(candidates, "candidates");

        WarInfoProvider selected = null;
        int selectedPriority = Integer.MIN_VALUE;
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.provider() == null) {
                continue;
            }
            // metadata() 抛异常的 provider 视为不兼容（ProviderCompatibility 内部已兜住）。
            if (!ProviderCompatibility.isCompatible(candidate.provider(), requirement)) {
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
