package com.xinantown.quest.town;

import com.xinantown.api.provider.PlayerTownProvider;
import com.xinantown.api.towny.PlayerTownSnapshot;
import com.xinantown.api.identity.PlayerId;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 玩家城邦查询桥接（经交互层）—— 本插件读取城邦信息的<b>唯一</b>入口。
 *
 * <p>写法与 {@code quest/war/WarInfoBridge} 一致：
 * <ul>
 *   <li>只依赖 xinantown-api 的只读契约 {@link PlayerTownProvider}，不再直连 Towny 内部类
 *       （{@code TownyAPI}）；</li>
 *   <li>provider 从 Bukkit {@code ServicesManager} 取，选取规则抽到纯函数 {@link TownProviderSelector}；</li>
 *   <li>交互层缺失（类不在运行环境）或 provider 读取失败时<b>安全降级</b>为
 *       {@link PlayerTownView#unknown()}，不抛异常、不影响委托系统其余功能。</li>
 * </ul>
 *
 * <p>共享契约类型只出现在受保护的方法体里（字段里不放 xinantown-api 类型），
 * 因此即使运行环境没有交互层，本类也能加载与实例化。
 */
public final class TownQueryBridge {

    private final ServicesManager services;
    private final Logger logger;
    private final ProviderHolder holder = new ProviderHolder();
    private boolean lookupFailureReported;
    private boolean readFailureReported;

    public TownQueryBridge(ServicesManager services, Logger logger) {
        this.services = services;
        this.logger = logger;
    }

    /**
     * 桥接为空（交互层类缺失时装配处降级为不建桥）时按「不知道」处理的安全入口。
     */
    public static PlayerTownView viewOf(TownQueryBridge bridge, UUID playerId) {
        return bridge == null ? PlayerTownView.unknown() : bridge.playerTown(playerId);
    }

    /** 交互层是否已有可用的玩家城邦 provider。结果缓存；不可用时不缓存空结果，下次调用重试。 */
    public boolean isAvailable() {
        return provider() != null;
    }

    /**
     * 玩家此刻站着的城邦。任何失败都降级为 {@link PlayerTownView#unknown()}，绝不抛异常。
     */
    public PlayerTownView playerTown(UUID playerId) {
        if (playerId == null) {
            return PlayerTownView.unknown();
        }
        try {
            PlayerTownProvider available = provider();
            if (available == null) {
                return PlayerTownView.unknown();
            }
            Optional<PlayerTownSnapshot> snapshot = available.playerTown(PlayerId.of(playerId));
            if (snapshot == null || snapshot.isEmpty()) {
                return PlayerTownView.unknown();   // 算不出来 ⇒ 未知，绝不伪装成「野外」
            }
            PlayerTownSnapshot value = snapshot.get();
            if (value.wilderness()) {
                return PlayerTownView.outsideTown();
            }
            return PlayerTownView.of(value.townId().value().toString(), value.townName(), value.isHomeTown());
        } catch (RuntimeException | LinkageError failure) {
            if (!readFailureReported) {
                readFailureReported = true;
                logger.warning("读取玩家城邦信息失败，按未知城邦降级: " + failure);
            }
            return PlayerTownView.unknown();
        }
    }

    private PlayerTownProvider provider() {
        if (holder.provider != null) {
            return holder.provider;
        }
        try {
            java.util.List<TownProviderSelector.Candidate> candidates = new java.util.ArrayList<>();
            for (RegisteredServiceProvider<PlayerTownProvider> registration
                    : services.getRegistrations(PlayerTownProvider.class)) {
                if (registration == null || registration.getProvider() == null) {
                    continue;
                }
                candidates.add(new TownProviderSelector.Candidate(registration.getProvider(),
                        registration.getPriority().ordinal()));
            }
            holder.provider = TownProviderSelector.select(Requirement.INSTANCE, candidates);
        } catch (LinkageError | RuntimeException failure) {
            holder.provider = null;
            if (!lookupFailureReported) {
                lookupFailureReported = true;
                logger.warning("XiNanTown 交互层玩家城邦 provider 不可用，城邦委托按未知城邦降级: " + failure);
            }
        }
        return holder.provider;
    }

    /** 延迟初始化持有者：把 xinantown-api 类型放在本类的静态初始化之外。 */
    private static final class ProviderHolder {
        private PlayerTownProvider provider;
    }

    /** 延迟初始化持有者：消费方对 provider 的契约要求（与 towny-bridge 注册的 id/版本/能力一致）。 */
    private static final class Requirement {
        private static final com.xinantown.api.provider.ProviderRequirement INSTANCE =
                new com.xinantown.api.provider.ProviderRequirement(
                        com.xinantown.api.provider.ProviderId.of("towny-bridge"),
                        com.xinantown.api.version.ApiVersion.of(2, 0, 0),
                        java.util.Set.of(com.xinantown.api.provider.V2Capability.PLAYER_TOWN));

        private Requirement() {
        }
    }
}
