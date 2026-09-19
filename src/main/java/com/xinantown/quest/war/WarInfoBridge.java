package com.xinantown.quest.war;

import com.xinantown.api.provider.ProviderId;
import com.xinantown.api.provider.ProviderRequirement;
import com.xinantown.api.provider.V2Capability;
import com.xinantown.api.provider.WarInfoProvider;
import com.xinantown.api.version.ApiVersion;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 战争信息桥接（经交互层）。
 *
 * <p>本类只依赖 xinantown-api 的只读契约 {@link WarInfoProvider}，不再反射读取 War 插件内部对象
 * （旧的 {@code getWarManager().getActiveWars()} 反射链已删除），也不再在编译期依赖 War.jar：
 * <ul>
 *   <li>战争事实由 War 模块注册到交互层（Bukkit {@code ServicesManager}）的只读 provider 提供；</li>
 *   <li>provider 的筛选规则抽到纯函数 {@link WarProviderSelector}，与交互层运行时一致；</li>
 *   <li>交互层缺失（连 xinantown-api 类都不在运行环境）或 provider 读取失败时降级为「无战争信息」，
 *       不影响委托系统正常运行，也不会向调用方抛异常。</li>
 * </ul>
 *
 * <p>每次 {@link #detect(List)} 只向 provider 取<b>一次</b>快照，再交给纯规则判定；
 * 改造前是「每条委托一次反射 + 一次全量战争列表扫描」。
 *
 * <p>共享契约类型只出现在受保护的方法体里，字段里不放 xinantown-api 类型
 * （沿用 {@code WarInfoProviderRegistration} 的写法），这样即使运行环境没有交互层，
 * 本类也能被加载与实例化，失败只会以 {@link LinkageError} 的形式出现在受保护的方法内。
 */
public final class WarInfoBridge {

    private final ServicesManager services;
    private final Logger logger;
    private final WarViolationDetector detector;
    private final ProviderHolder holder = new ProviderHolder();
    private boolean lookupFailureReported;

    public WarInfoBridge(ServicesManager services, Logger logger, WarViolationDetector detector) {
        this.services = services;
        this.logger = logger;
        this.detector = detector;
    }

    /**
     * 交互层是否已有可用的战争信息 provider。
     * 结果缓存；不可用时不缓存「空结果」，下次调用重试，因此不受插件加载顺序影响。
     */
    public boolean isAvailable() {
        return provider() != null;
    }

    /**
     * 取一次战争快照并判定违约。
     *
     * <p>provider 不可用、读取失败或返回异常数据时返回空列表（降级为「无战争信息」），
     * 绝不向调用方抛异常。
     *
     * @param candidates 已接取的城邦委托
     * @return 本轮首次判定出的违约
     */
    public List<WarViolationDetector.Violation> detect(List<WarViolationDetector.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        try {
            WarInfoProvider available = provider();
            if (available == null) {
                return List.of();
            }
            return detector.detect(candidates, available.warState());
        } catch (RuntimeException | LinkageError failure) {
            logger.warning("读取战争数据失败，本轮违约检测跳过: " + failure);
            return List.of();
        }
    }

    /**
     * 交互层只读 provider。
     *
     * <p>选择规则与交互层运行时一致：先按 provider id / API 版本 / 能力过滤兼容性，
     * 再取 Bukkit 优先级最高者，高优先级但不兼容的注册不会遮蔽低优先级的兼容注册。
     */
    private WarInfoProvider provider() {
        if (holder.provider != null) {
            return holder.provider;
        }
        try {
            List<WarProviderSelector.Candidate> candidates = new ArrayList<>();
            for (RegisteredServiceProvider<WarInfoProvider> registration
                    : services.getRegistrations(WarInfoProvider.class)) {
                if (registration == null || registration.getProvider() == null) {
                    continue;
                }
                candidates.add(new WarProviderSelector.Candidate(registration.getProvider(),
                        registration.getPriority().ordinal()));
            }
            holder.provider = WarProviderSelector.select(Requirement.INSTANCE, candidates);
        } catch (LinkageError | RuntimeException failure) {
            // 交互层不在运行环境：降级为无战争信息（不缓存失败结果，加载顺序变化后可自愈）
            holder.provider = null;
            if (!lookupFailureReported) {
                lookupFailureReported = true;
                logger.warning("XiNanTown 交互层战争信息 provider 不可用，违约检测降级为关闭: " + failure);
            }
        }
        return holder.provider;
    }

    /**
     * 延迟初始化持有者：把 xinantown-api 类型放在本类的静态初始化之外，
     * 这样即使运行环境没有交互层，{@link WarInfoBridge} 类本身也能正常加载与初始化。
     */
    private static final class ProviderHolder {
        private WarInfoProvider provider;
    }

    /** 延迟初始化持有者：消费方对 provider 的契约要求。 */
    private static final class Requirement {
        private static final ProviderRequirement INSTANCE = new ProviderRequirement(
                ProviderId.of("war"),
                ApiVersion.of(2, 0, 0),
                Set.of(V2Capability.WAR_INFO));

        private Requirement() {
        }
    }
}
