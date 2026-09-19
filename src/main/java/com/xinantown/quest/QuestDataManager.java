package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

public class QuestDataManager {

    private final Logger logger;
    private final File dataFile;
    private List<Quest> cache;
    /**
     * 委托数据变更回调。所有委托状态变更都必然经过 {@link #saveAll}，因此这是本插件
     * 「委托事实发生变化」的完整信号（用于唤醒依赖委托状态的检测，避免无条件轮询）。
     */
    private volatile Runnable changeListener;

    public QuestDataManager(File dataFile, Logger logger) {
        this.dataFile = dataFile;
        this.logger = logger;
    }

    /**
     * 注册委托数据变更回调；每次成功写入后调用一次。
     *
     * <p>回调抛出的异常会被吞掉并记日志，不会影响委托本身的保存行为。
     *
     * @param listener 回调，传 {@code null} 表示取消注册
     */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    public synchronized List<Quest> loadAll() {
        if (cache != null) return new ArrayList<>(cache);
        cache = loadFromDisk();
        return new ArrayList<>(cache);
    }

    public synchronized void saveAll(List<Quest> quests) {
        this.cache = new ArrayList<>(quests);
        saveToDisk(quests);
        notifyChanged();
    }

    private void notifyChanged() {
        Runnable listener = changeListener;
        if (listener == null) return;
        try {
            listener.run();
        } catch (RuntimeException failure) {
            logger.warning("Quest change listener failed: " + failure);
        }
    }

    private List<Quest> loadFromDisk() {
        List<Quest> result = new ArrayList<>();
        if (!dataFile.exists()) return result;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = cfg.getConfigurationSection("quests");
        if (sec == null) return result;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection q = sec.getConfigurationSection(key);
            if (q == null) continue;
            try {
                result.add(new Quest(
                        UUID.fromString(key),
                        q.getString("title", ""),
                        UUID.fromString(q.getString("publisherId", "")),
                        q.getString("publisherName", ""),
                        q.getBoolean("isTownQuest", false),
                        q.getString("questType", "material"),
                        q.getString("description", ""),
                        q.getDouble("reward", 0),
                        q.getDouble("deposit", 0),
                        q.getLong("acceptDeadline", 0),
                        q.getLong("completeDeadline", 0),
                        QuestStatus.valueOf(q.getString("status", "OPEN")),
                        q.contains("acceptorId") ? UUID.fromString(q.getString("acceptorId")) : null,
                        q.getString("acceptorName", null),
                        q.getBoolean("isTownAcceptor", false),
                        q.getInt("rejectCount", 0)));
            } catch (Exception e) {
                logger.warning("Skipping invalid quest entry: " + key);
            }
        }
        return result;
    }

    private void saveToDisk(List<Quest> quests) {
        YamlConfiguration cfg = new YamlConfiguration();
        ConfigurationSection sec = cfg.createSection("quests");
        for (Quest q : quests) {
            ConfigurationSection qs = sec.createSection(q.id().toString());
            qs.set("title", q.title());
            qs.set("publisherId", q.publisherId().toString());
            qs.set("publisherName", q.publisherName());
            qs.set("isTownQuest", q.isTownQuest());
            qs.set("questType", q.questType());
            qs.set("description", q.description());
            qs.set("reward", q.reward());
            qs.set("deposit", q.deposit());
            qs.set("acceptDeadline", q.acceptDeadline());
            qs.set("completeDeadline", q.completeDeadline());
            qs.set("status", q.status().name());
            if (q.acceptorId() != null) qs.set("acceptorId", q.acceptorId().toString());
            if (q.acceptorName() != null) qs.set("acceptorName", q.acceptorName());
            qs.set("isTownAcceptor", q.isTownAcceptor());
            qs.set("rejectCount", q.rejectCount());
        }
        try { cfg.save(dataFile); } catch (IOException e) {
            logger.severe("Failed to save quests: " + e.getMessage());
        }
    }
}
