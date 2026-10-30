package com.xinantown.quest.town;

import com.xinantown.api.capability.CapabilityStatus;
import com.xinantown.api.identity.PlayerId;
import com.xinantown.api.identity.TownId;
import com.xinantown.api.provider.PlayerTownProvider;
import com.xinantown.api.provider.ProviderId;
import com.xinantown.api.provider.ProviderIdentity;
import com.xinantown.api.provider.ProviderMetadata;
import com.xinantown.api.provider.V2Capability;
import com.xinantown.api.provider.V2CapabilitySet;
import com.xinantown.api.towny.PlayerTownSnapshot;
import com.xinantown.api.version.ApiVersion;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 交互层城邦查询桥接：provider 缺失/异常时的<b>安全降级</b>，以及兼容注册的选取。
 *
 * <p>不启服务端：{@link ServicesManager} 用动态代理只回答 {@code getRegistrations(Class)}，
 * provider 是本测试内的假实现（只实现契约，不碰 Towny）。
 */
class TownQueryBridgeTest {

    private static final Logger LOGGER = Logger.getLogger("TownQueryBridgeTest");
    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID TOWN_UUID = UUID.randomUUID();

    // ==================== 假 provider ====================

    private record FakeProvider(ProviderMetadata metadata, Map<UUID, Optional<PlayerTownSnapshot>> answers,
                                RuntimeException boom) implements PlayerTownProvider {

        static FakeProvider of(ProviderId id, ApiVersion version, Set<V2Capability> capabilities,
                               Map<UUID, Optional<PlayerTownSnapshot>> answers) {
            return new FakeProvider(buildMetadata(id, version, capabilities), answers, null);
        }

        static FakeProvider exploding() {
            return new FakeProvider(buildMetadata(ProviderId.of("towny-bridge"), ApiVersion.of(2, 0, 0),
                    Set.of(V2Capability.PLAYER_TOWN)), Map.of(), new IllegalStateException("towny read failed"));
        }

        private static ProviderMetadata buildMetadata(ProviderId id, ApiVersion version,
                                                 Set<V2Capability> capabilities) {
            var statuses = new java.util.EnumMap<V2Capability, CapabilityStatus>(V2Capability.class);
            capabilities.forEach(capability -> statuses.put(capability, CapabilityStatus.AVAILABLE));
            return new ProviderMetadata(new ProviderIdentity(id, "test"), version,
                    V2CapabilitySet.of(statuses));
        }

        @Override
        public Optional<PlayerTownSnapshot> playerTown(PlayerId playerId) {
            if (boom != null) {
                throw boom;
            }
            if (playerId == null) {
                return Optional.empty();
            }
            return answers.getOrDefault(playerId.value(), Optional.empty());
        }

        @Override
        public Set<String> ownedDisplaySources() {
            return Set.of();
        }
    }

    /** 只回答 {@code getRegistrations(Class)} 的服务管理器替身。 */
    private static ServicesManager servicesWith(List<RegisteredServiceProvider<PlayerTownProvider>> registrations) {
        return (ServicesManager) Proxy.newProxyInstance(
                TownQueryBridgeTest.class.getClassLoader(),
                new Class<?>[]{ServicesManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getRegistrations")) {
                        return registrations;
                    }
                    return null;
                });
    }

    private static RegisteredServiceProvider<PlayerTownProvider> registered(
            PlayerTownProvider provider, ServicePriority priority) {
        return new RegisteredServiceProvider<>(PlayerTownProvider.class, provider, priority, null);
    }

    private static TownQueryBridge bridge(PlayerTownProvider... providers) {
        List<RegisteredServiceProvider<PlayerTownProvider>> registrations = new ArrayList<>();
        for (PlayerTownProvider provider : providers) {
            registrations.add(registered(provider, ServicePriority.Normal));
        }
        return new TownQueryBridge(servicesWith(registrations), LOGGER);
    }

    private static Map<UUID, Optional<PlayerTownSnapshot>> townSnapshot(String name, boolean homeTown) {
        return Map.of(PLAYER, Optional.of(PlayerTownSnapshot.town(
                TownId.of(TOWN_UUID), name, homeTown, "§b[" + name + "]")));
    }

    // ==================== 安全降级 ====================

    @Test
    void providerMissing_degradesToUnknownAndNeverThrows() {
        TownQueryBridge bridge = bridge();
        assertFalse(bridge.isAvailable());
        PlayerTownView view = bridge.playerTown(PLAYER);
        assertFalse(view.available());
        assertFalse(view.wilderness());
        assertNull(view.townId());
        assertFalse(view.hasTown());
    }

    @Test
    void nullBridge_nullPlayerId_areUnknown() {
        assertFalse(TownQueryBridge.viewOf(null, PLAYER).available());
        assertFalse(bridge().playerTown(null).available());
    }

    @Test
    void providerThrowingRuntimeException_degradesToUnknown() {
        PlayerTownView view = bridge(FakeProvider.exploding()).playerTown(PLAYER);
        assertFalse(view.available());
        assertFalse(view.hasTown());
    }

    @Test
    void emptyOptional_isUnknownAndNeverReportedAsWilderness() {
        FakeProvider provider = FakeProvider.of(ProviderId.of("towny-bridge"), ApiVersion.of(2, 0, 0),
                Set.of(V2Capability.PLAYER_TOWN), Map.of(PLAYER, Optional.empty()));
        PlayerTownView view = bridge(provider).playerTown(PLAYER);
        assertFalse(view.available());
        assertFalse(view.wilderness(), "算不出来 ≠ 站在野外：契约要求两者严格区分");
    }

    // ==================== 正常取值 ====================

    @Test
    void providerPresent_returnsStableTownBinding() {
        FakeProvider provider = FakeProvider.of(ProviderId.of("towny-bridge"), ApiVersion.of(2, 0, 0),
                Set.of(V2Capability.PLAYER_TOWN), townSnapshot("Alpha", true));

        TownQueryBridge bridge = bridge(provider);
        assertTrue(bridge.isAvailable());
        PlayerTownView view = bridge.playerTown(PLAYER);
        assertTrue(view.hasTown());
        assertEquals(TOWN_UUID.toString(), view.townId());
        assertEquals("Alpha", view.townName());
        assertTrue(view.homeTown());
    }

    @Test
    void wildernessSnapshot_isASuccessfulAnswerNotUnknown() {
        FakeProvider provider = FakeProvider.of(ProviderId.of("towny-bridge"), ApiVersion.of(2, 0, 0),
                Set.of(V2Capability.PLAYER_TOWN),
                Map.of(PLAYER, Optional.of(PlayerTownSnapshot.wilderness("§7荒野"))));

        PlayerTownView view = bridge(provider).playerTown(PLAYER);
        assertTrue(view.available());
        assertTrue(view.wilderness());
        assertFalse(view.hasTown());
    }

    // ==================== 选取规则 ====================

    @Test
    void incompatibleRegistration_isIgnored() {
        FakeProvider wrongId = FakeProvider.of(ProviderId.of("war"), ApiVersion.of(2, 0, 0),
                Set.of(V2Capability.PLAYER_TOWN), townSnapshot("Alpha", true));
        FakeProvider missingCapability = FakeProvider.of(ProviderId.of("towny-bridge"), ApiVersion.of(2, 0, 0),
                Set.of(V2Capability.WAR_INFO), townSnapshot("Alpha", true));
        FakeProvider tooOldApi = FakeProvider.of(ProviderId.of("towny-bridge"), ApiVersion.of(1, 0, 0),
                Set.of(V2Capability.PLAYER_TOWN), townSnapshot("Alpha", true));

        for (FakeProvider provider : List.of(wrongId, missingCapability, tooOldApi)) {
            TownQueryBridge bridge = bridge(provider);
            assertFalse(bridge.isAvailable(), "不兼容的注册必须被忽略: " + provider.metadata().identity());
            assertFalse(bridge.playerTown(PLAYER).hasTown());
        }
    }

    @Test
    void highestPriorityCompatibleRegistrationWins() {
        FakeProvider low = FakeProvider.of(ProviderId.of("towny-bridge"), ApiVersion.of(2, 0, 0),
                Set.of(V2Capability.PLAYER_TOWN), townSnapshot("LowPriority", true));
        FakeProvider high = FakeProvider.of(ProviderId.of("towny-bridge"), ApiVersion.of(2, 0, 0),
                Set.of(V2Capability.PLAYER_TOWN), townSnapshot("HighPriority", true));
        FakeProvider incompatible = FakeProvider.of(ProviderId.of("war"), ApiVersion.of(2, 0, 0),
                Set.of(V2Capability.PLAYER_TOWN), townSnapshot("Incompatible", true));

        List<RegisteredServiceProvider<PlayerTownProvider>> registrations = List.of(
                registered(low, ServicePriority.Low),
                registered(incompatible, ServicePriority.Highest),
                registered(high, ServicePriority.Normal));

        PlayerTownView view = new TownQueryBridge(servicesWith(registrations), LOGGER).playerTown(PLAYER);
        assertEquals("HighPriority", view.townName());
    }
}
