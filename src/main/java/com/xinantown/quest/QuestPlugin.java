package com.xinantown.quest;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class QuestPlugin extends JavaPlugin {

    private QuestDataManager dataManager;
    private WarehouseManager warehouseManager;
    private ViolationManager violationManager;
    private QuestGuiManager guiManager;
    private BoardManager boardManager;
    private BoardMenuHandler boardMenuHandler;
    private BoardDisplayManager boardDisplayManager;
    private BoardAcceptHandler boardAcceptHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataManager = new QuestDataManager(new File(getDataFolder(), "quests.yml"), getLogger());
        warehouseManager = new WarehouseManager(getDataFolder(), getLogger());
        violationManager = new ViolationManager(getDataFolder(), getLogger());
        boardManager = new BoardManager(getDataFolder(), getLogger());

        QuestCommand cmd = new QuestCommand(this);
        getCommand("quest").setExecutor(cmd);
        getCommand("quest").setTabCompleter(cmd);

        guiManager = new QuestGuiManager(this);
        getServer().getPluginManager().registerEvents(guiManager, this);
        var boardListener = new BoardListener(boardManager);
        getServer().getPluginManager().registerEvents(boardListener, this);
        boardListener.restoreCenterBoards();

        boardDisplayManager = new BoardDisplayManager(this, boardManager, dataManager);
        boardDisplayManager.start();

        boardAcceptHandler = new BoardAcceptHandler(this, boardManager, boardDisplayManager, dataManager);
        getServer().getPluginManager().registerEvents(boardAcceptHandler, this);

        boardMenuHandler = new BoardMenuHandler(this, boardManager, dataManager);
        getServer().getPluginManager().registerEvents(boardMenuHandler, this);

        getServer().getPluginManager().registerEvents(
                new DisplayUpdateListener(boardDisplayManager), this);

        new QuestScheduler(this).start();
        new CasusBelliListener(this).register();

        getLogger().info("QuestPlugin enabled.");
    }

    @Override
    public void onDisable() {
        if (boardDisplayManager != null) boardDisplayManager.stop();
        getLogger().info("QuestPlugin disabled.");
    }

    public QuestDataManager getDataManager() { return dataManager; }
    public WarehouseManager getWarehouseManager() { return warehouseManager; }
    public QuestGuiManager getGuiManager() { return guiManager; }
    public ViolationManager getViolationManager() { return violationManager; }
    public BoardManager getBoardManager() { return boardManager; }
    public BoardDisplayManager getBoardDisplayManager() { return boardDisplayManager; }
    public BoardMenuHandler getBoardMenuHandler() { return boardMenuHandler; }
}
