package com.xinantown.quest;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class QuestPlugin extends JavaPlugin {

    private QuestDataManager dataManager;
    private WarehouseManager warehouseManager;
    private ViolationManager violationManager;
    private QuestGuiManager guiManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataManager = new QuestDataManager(new File(getDataFolder(), "quests.yml"), getLogger());
        warehouseManager = new WarehouseManager(getDataFolder(), getLogger());
        violationManager = new ViolationManager(getDataFolder(), getLogger());

        QuestCommand cmd = new QuestCommand(this);
        getCommand("quest").setExecutor(cmd);
        getCommand("quest").setTabCompleter(cmd);

        guiManager = new QuestGuiManager(this);
        getServer().getPluginManager().registerEvents(guiManager, this);

        new QuestScheduler(this).start();

        getLogger().info("QuestPlugin enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("QuestPlugin disabled.");
    }

    public QuestDataManager getDataManager() { return dataManager; }
    public WarehouseManager getWarehouseManager() { return warehouseManager; }
    public QuestGuiManager getGuiManager() { return guiManager; }
    public ViolationManager getViolationManager() { return violationManager; }
}
