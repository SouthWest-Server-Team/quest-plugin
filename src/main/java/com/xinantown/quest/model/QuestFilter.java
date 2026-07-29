package com.xinantown.quest.model;

/**
 * Display board quest type filter.
 */
public enum QuestFilter {
    ALL,       // show both personal and town quests
    PERSONAL,  // only personal quests
    TOWN;      // only town quests

    public static QuestFilter fromString(String s) {
        if (s == null) return ALL;
        return switch (s.toLowerCase()) {
            case "personal" -> PERSONAL;
            case "town" -> TOWN;
            default -> ALL;
        };
    }
}
