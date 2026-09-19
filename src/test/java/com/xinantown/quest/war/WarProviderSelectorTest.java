package com.xinantown.quest.war;

import com.xinantown.api.capability.CapabilityStatus;
import com.xinantown.api.provider.ProviderId;
import com.xinantown.api.provider.ProviderIdentity;
import com.xinantown.api.provider.ProviderMetadata;
import com.xinantown.api.provider.ProviderRequirement;
import com.xinantown.api.provider.V2Capability;
import com.xinantown.api.provider.V2CapabilitySet;
import com.xinantown.api.provider.WarInfoProvider;
import com.xinantown.api.version.ApiVersion;
import com.xinantown.api.war.WarStateSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 纯选取规则测试：不接触 Bukkit（注册枚举留在 WarInfoBridge 里，选取规则本身可独立测试）。
 */
class WarProviderSelectorTest {

    private static final ProviderRequirement REQUIREMENT = new ProviderRequirement(
            ProviderId.of("war"), ApiVersion.of(2, 0, 0), Set.of(V2Capability.WAR_INFO));

    private static WarInfoProvider provider(String id, ApiVersion version,
                                            V2Capability capability, CapabilityStatus status) {
        return new WarInfoProvider() {
            @Override
            public ProviderMetadata metadata() {
                return new ProviderMetadata(new ProviderIdentity(ProviderId.of(id), "test"),
                        version, V2CapabilitySet.of(Map.of(capability, status)));
            }

            @Override
            public WarStateSnapshot warState() {
                return WarStateSnapshot.empty();
            }
        };
    }

    private static WarInfoProvider warProvider(ApiVersion version) {
        return provider("war", version, V2Capability.WAR_INFO, CapabilityStatus.AVAILABLE);
    }

    private static WarProviderSelector.Candidate candidate(WarInfoProvider provider, int priority) {
        return new WarProviderSelector.Candidate(provider, priority);
    }

    @Test
    void selectsCompatibleProvider() {
        WarInfoProvider compatible = warProvider(ApiVersion.of(2, 0, 0));
        assertSame(compatible, WarProviderSelector.select(REQUIREMENT, List.of(candidate(compatible, 0))));
    }

    @Test
    void rejectsProviderWithDifferentId() {
        WarInfoProvider other = provider("other", ApiVersion.of(2, 0, 0),
                V2Capability.WAR_INFO, CapabilityStatus.AVAILABLE);
        assertNull(WarProviderSelector.select(REQUIREMENT, List.of(candidate(other, 0))));
    }

    @Test
    void rejectsProviderWithIncompatibleMajorVersion() {
        assertNull(WarProviderSelector.select(REQUIREMENT,
                List.of(candidate(warProvider(ApiVersion.of(1, 9, 0)), 0))));
    }

    @Test
    void acceptsNewerMinorVersion() {
        WarInfoProvider newer = warProvider(ApiVersion.of(2, 5, 0));
        assertSame(newer, WarProviderSelector.select(REQUIREMENT, List.of(candidate(newer, 0))));
    }

    @Test
    void rejectsProviderWithoutWarInfoCapability() {
        WarInfoProvider withoutCapability = provider("war", ApiVersion.of(2, 0, 0),
                V2Capability.NPC_CORE, CapabilityStatus.AVAILABLE);
        assertNull(WarProviderSelector.select(REQUIREMENT, List.of(candidate(withoutCapability, 0))));
    }

    @Test
    void rejectsProviderWhoseWarInfoCapabilityIsUnavailable() {
        WarInfoProvider unavailable = provider("war", ApiVersion.of(2, 0, 0),
                V2Capability.WAR_INFO, CapabilityStatus.UNAVAILABLE);
        assertNull(WarProviderSelector.select(REQUIREMENT, List.of(candidate(unavailable, 0))));
    }

    @Test
    void providerWhoseMetadataThrowsIsTreatedAsIncompatible() {
        WarInfoProvider broken = new WarInfoProvider() {
            @Override
            public ProviderMetadata metadata() {
                throw new IllegalStateException("metadata unavailable");
            }

            @Override
            public WarStateSnapshot warState() {
                return WarStateSnapshot.empty();
            }
        };
        WarInfoProvider compatible = warProvider(ApiVersion.of(2, 0, 0));

        assertSame(compatible, WarProviderSelector.select(REQUIREMENT,
                List.of(candidate(broken, 5), candidate(compatible, 0))));
    }

    @Test
    void highestPriorityCompatibleProviderWins() {
        WarInfoProvider low = warProvider(ApiVersion.of(2, 0, 0));
        WarInfoProvider high = warProvider(ApiVersion.of(2, 1, 0));

        assertSame(high, WarProviderSelector.select(REQUIREMENT,
                List.of(candidate(low, 0), candidate(high, 3))));
    }

    @Test
    void highPriorityIncompatibleRegistrationDoesNotShadowCompatibleOne() {
        WarInfoProvider incompatible = provider("other", ApiVersion.of(9, 0, 0),
                V2Capability.NPC_CORE, CapabilityStatus.AVAILABLE);
        WarInfoProvider compatible = warProvider(ApiVersion.of(2, 0, 0));

        assertSame(compatible, WarProviderSelector.select(REQUIREMENT,
                List.of(candidate(incompatible, 5), candidate(compatible, 0))));
    }

    @Test
    void emptyRegistrationsYieldNoProvider() {
        assertNull(WarProviderSelector.select(REQUIREMENT, List.of()));
    }

    @Test
    void nullEntriesAreSkipped() {
        WarInfoProvider compatible = warProvider(ApiVersion.of(2, 0, 0));
        List<WarProviderSelector.Candidate> candidates = new ArrayList<>();
        candidates.add(null);
        candidates.add(candidate(compatible, 0));

        assertSame(compatible, WarProviderSelector.select(REQUIREMENT, candidates));
    }

    @Test
    void samePriorityKeepsFirstCompatibleRegistration() {
        WarInfoProvider first = warProvider(ApiVersion.of(2, 0, 0));
        WarInfoProvider second = warProvider(ApiVersion.of(2, 2, 0));

        assertSame(first, WarProviderSelector.select(REQUIREMENT,
                List.of(candidate(first, 1), candidate(second, 1))));
    }

    @Test
    void requirementIsMatchedExactlyOnProviderId() {
        assertEquals("war", REQUIREMENT.expectedProvider().value());
    }
}
