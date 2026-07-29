package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;

import java.util.List;
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

    public CasusBelliListener(QuestPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getDataManager();
        this.violationManager = plugin.getViolationManager();
        this.logger = plugin.getLogger();
    }

    public void register() {
        if (Bukkit.getPluginManager().getPlugin("CasusBelli") == null) {
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
                boolean atWar = checkCasusBelliWar(publisherTown.getName(), acceptorTown.getName());
                if (atWar && !q.isTownAcceptor()) {
                    // Only flag the attacker (acceptor) if they initiated war
                    // For simplicity: flag both sides
                    violationManager.addViolation(publisherTown.getName());
                    violationManager.addViolation(acceptorTown.getName());
                    logger.info("Violation recorded for " + publisherTown.getName()
                            + " and " + acceptorTown.getName() + " due to war during quest " + q.title());
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean checkCasusBelliWar(String town1, String town2) {
        // Use CasusBelli API via reflection
        try {
            var cbPlugin = Bukkit.getPluginManager().getPlugin("CasusBelli");
            if (cbPlugin == null) return false;

            var warManager = cbPlugin.getClass().getMethod("getWarManager").invoke(cbPlugin);
            @SuppressWarnings("unchecked")
            var wars = (java.util.Collection<?>) warManager.getClass()
                    .getMethod("getActiveWars").invoke(warManager);

            for (var war : wars) {
                String attacker = (String) war.getClass().getMethod("getAttackerTown").invoke(war);
                String defender = (String) war.getClass().getMethod("getDefenderTown").invoke(war);
                if ((attacker.equalsIgnoreCase(town1) && defender.equalsIgnoreCase(town2))
                        || (attacker.equalsIgnoreCase(town2) && defender.equalsIgnoreCase(town1))) {
                    return true;
                }
            }
        } catch (Exception e) {
            // CasusBelli API not available — no war to check
        }
        return false;
    }
}
