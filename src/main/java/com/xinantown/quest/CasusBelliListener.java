package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Listens for CasusBelli war start events and triggers violations
 * when warring towns are involved in an active quest together.
 * Registered via reflection since CasusBelli event classes aren't available at compile time.
 */
public class CasusBelliListener {

    private final QuestPlugin plugin;
    private final QuestDataManager dataManager;
    private final ViolationManager violationManager;
    private final Logger logger;
    /** Prevents the 30-second polling task from punishing the same quest/war pair repeatedly. */
    private final Set<String> processedWarQuestPairs = new HashSet<>();

    public CasusBelliListener(QuestPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
        this.violationManager = plugin.getViolationManager();
        this.logger = plugin.getLogger();
    }

    public void register() {
        if (Bukkit.getPluginManager().getPlugin("War") == null) {
            logger.info("CasusBelli not found — violation integration disabled.");
            return;
        }

        try {
            // Try to listen for CasusBelli custom war event
            Class<? extends Event> eventClass = Class.forName(
                    "org.bukkit.event.server.PluginEnableEvent")
                    .asSubclass(Event.class);

            // Instead, listen for Towny events that indicate war start
            // CasusBelli uses Towny; we can watch for town relation changes
            logger.info("CasusBelli integration: using scheduled check (30s).");

            // Fallback: use scheduler to detect CasusBelli wars
            Bukkit.getScheduler().runTaskTimer(plugin, this::checkForWars, 600L, 600L);

        } catch (Exception e) {
            logger.warning("Failed to register CasusBelli listener: " + e.getMessage());
        }
    }

    /**
     * Check all active quests for warring towns.
     * If two towns that are party to an ACCEPTED quest are now at war,
     * the attacking town gets a violation.
     */
    private void checkForWars() {
        List<Quest> all = new java.util.ArrayList<>(dataManager.loadAll());
        boolean changed = false;

        for (int i = 0; i < all.size(); i++) {
            Quest q = all.get(i);
            if (q.status() != QuestStatus.ACCEPTED) continue;
            if (!q.isTownQuest()) continue;
            if (q.acceptorId() == null) continue;

            // Check if publisher and acceptor towns are at war
            try {
                var publisherPlayer = Bukkit.getOfflinePlayer(q.publisherId());
                var acceptorPlayer = Bukkit.getOfflinePlayer(q.acceptorId());
                var publisherTown = com.palmergames.bukkit.towny.TownyAPI.getInstance()
                        .getTown(publisherPlayer.getUniqueId());
                var acceptorTown = com.palmergames.bukkit.towny.TownyAPI.getInstance()
                        .getTown(acceptorPlayer.getUniqueId());

                if (publisherTown == null || acceptorTown == null) continue;

                // Check for active CasusBelli war between these towns
                String attackerTown = findActiveWarAttacker(publisherTown.getName(), acceptorTown.getName());
                String processedKey = q.id() + ":" + publisherTown.getName().toLowerCase() + ":" + acceptorTown.getName().toLowerCase();
                if (attackerTown != null && processedWarQuestPairs.add(processedKey)) {
                    violationManager.addViolation(attackerTown);
                    logger.info("Violation recorded only for declaring town " + attackerTown
                            + " due to war during quest " + q.title());
                }
            } catch (Exception ignored) {}
        }
    }

    private String findActiveWarAttacker(String town1, String town2) {
        // Use the published War API via reflection; only an ACTIVE war can cause a violation.
        try {
            var cbPlugin = Bukkit.getPluginManager().getPlugin("War");
            if (cbPlugin == null) return null;

            var warManager = cbPlugin.getClass().getMethod("getWarManager").invoke(cbPlugin);
            @SuppressWarnings("unchecked")
            var wars = (java.util.Collection<?>) warManager.getClass()
                    .getMethod("getActiveWars").invoke(warManager);

            for (var war : wars) {
                Object status = war.getClass().getMethod("getStatus").invoke(war);
                if (status == null || !"ACTIVE".equals(status.toString())) continue;
                String attacker = (String) war.getClass().getMethod("getAttackerTown").invoke(war);
                String defender = (String) war.getClass().getMethod("getDefenderTown").invoke(war);
                if ((attacker.equalsIgnoreCase(town1) && defender.equalsIgnoreCase(town2))
                        || (attacker.equalsIgnoreCase(town2) && defender.equalsIgnoreCase(town1))) {
                    return attacker;
                }
            }
        } catch (Exception e) {
            // CasusBelli API not available — no war to check
        }
        return null;
    }
}
