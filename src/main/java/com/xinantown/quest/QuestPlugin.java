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
    private CasusBelliListener casusBelliListener;
    private com.xinantown.quest.town.TownQueryBridge townQueryBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataManager = new QuestDataManager(new File(getDataFolder(), "quests.yml"), getLogger());
        warehouseManager = new WarehouseManager(getDataFolder(), getLogger());
        violationManager = new ViolationManager(getDataFolder(), getLogger());
        boardManager = new BoardManager(getDataFolder(), getLogger());
        townQueryBridge = createTownQueryBridge(this);
        reportLegacyUnownedTownQuests();

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
        casusBelliListener = new CasusBelliListener(this);
        casusBelliListener.register();

        getLogger().info("QuestPlugin enabled.");
    }

    @Override
    public void onDisable() {
        if (boardDisplayManager != null) boardDisplayManager.stop();
        if (casusBelliListener != null) casusBelliListener.stop();
        getLogger().info("QuestPlugin disabled.");
    }

    public QuestDataManager getDataManager() { return dataManager; }
    public com.xinantown.quest.town.TownQueryBridge getTownQueryBridge() { return townQueryBridge; }

    /**
     * 建立交互层城邦查询桥；运行环境没有交互层时降级为 null（城邦查询一律按「未知城邦」处理，
     * 个人委托与其余功能不受影响）。
     */
    private static com.xinantown.quest.town.TownQueryBridge createTownQueryBridge(QuestPlugin plugin) {
        try {
            return new com.xinantown.quest.town.TownQueryBridge(
                    plugin.getServer().getServicesManager(), plugin.getLogger());
        } catch (LinkageError | RuntimeException failure) {
            plugin.getLogger().warning("XiNanTown 交互层不可用，城邦查询按未知城邦降级: " + failure);
            return null;
        }
    }

    /**
     * 改造前落盘的城邦委托没有归属城邦：数据保留（不静默丢弃），但不再出现在任何城的公告板上、也不可接取。
     * 启动时如实告警一次，让服主看见这些历史条目。
     */
    private void reportLegacyUnownedTownQuests() {
        long unowned = dataManager.loadAll().stream()
                .filter(com.xinantown.quest.town.QuestTownPolicy::isUnownedTownQuest)
                .count();
        if (unowned > 0) {
            getLogger().warning("检测到 " + unowned + " 条改造前遗留的无归属城邦委托：数据保留、"
                    + "发布者仍可在 /quest my 看到、过期仍会退还押金，"
                    + "但不会出现在任何城邦的公告板上，也不允许任何人接取。");
        }
    }
    public WarehouseManager getWarehouseManager() { return warehouseManager; }
    public QuestGuiManager getGuiManager() { return guiManager; }
    public ViolationManager getViolationManager() { return violationManager; }
    public BoardManager getBoardManager() { return boardManager; }
    public BoardDisplayManager getBoardDisplayManager() { return boardDisplayManager; }
    public BoardMenuHandler getBoardMenuHandler() { return boardMenuHandler; }
}
