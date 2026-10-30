package com.xinantown.quest;

import com.palmergames.bukkit.towny.TownyAPI;
import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicesManager;
import org.mockito.MockedStatic;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * <b>结算调用点测试台</b>：把「钱到底怎么走」变成可断言的账本。
 *
 * <p>做法（不改任何生产代码）：</p>
 * <ul>
 *   <li>经济系统替身：{@code Economy} 被 Mockito mock，{@code withdrawPlayer}/{@code depositPlayer}
 *       每次调用按顺序记成 {@link Money} 一条账，返回 SUCCESS；{@code has()} 可开关，用来验证 fail-closed 守卫。</li>
 *   <li>Bukkit 静态替身：{@code mockStatic(Bukkit.class)} 提供 {@code ServicesManager}（注册经济替身）、
 *       {@code getOfflinePlayer}、{@code getPlayer}（一律 null，跳过在线播报）、{@code getPluginManager}（事件黑洞）、
 *       {@code getItemFactory}（委托卷的 ItemMeta 替身）。</li>
 *   <li>Towny 静态替身：{@code mockStatic(TownyAPI.class)}，{@code getTown()} 返回 null —— 个人委托，
 *       不触发城邦违约记账（这条不对称另有用例钉住）。</li>
 *   <li>私有方法缝隙：{@code handleApprove/handleTerminate/handleCancel/accept} 是 private，
 *       用 {@link #invokePrivate} 反射调用；入口事件（{@code InventoryClickEvent}）与金额无关，不在这里复刻。</li>
 * </ul>
 *
 * <p>基准数据与 {@code SettlementRulesCharacterizationTest} 一致：{@code deposit=100 / reward=500}、
 * 发布方取消系数 {@code 0.5}、接取方取消与超时系数 {@code 3.0}。</p>
 */
final class SettlementHarness implements AutoCloseable {

    /** 账本一条：{@code op=WITHDRAW|DEPOSIT}、{@code who=玩家 UUID}、{@code amount=金额}。 */
    record Money(String op, UUID who, double amount) {
    }

    private final MockedStatic<Bukkit> bukkit;
    private final MockedStatic<TownyAPI> towny;
    private final org.mockito.MockedConstruction<QuestScroll> scrolls;

    private QuestPlugin plugin;
    private QuestDataManager data;
    private WarehouseManager warehouse;
    private ViolationManager violations;
    private BoardManager boardManager;
    private Economy economy;
    private FileConfiguration config;
    private final Map<String, Object> configValues = new HashMap<>();

    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final List<Money> ledger = new ArrayList<>();
    private boolean canAfford = true;
    private boolean economyAvailable = true;

    SettlementHarness(Path dataDir) {
        bukkit = mockStatic(Bukkit.class);
        towny = mockStatic(TownyAPI.class);
        // 委托卷（BOOK + PDC）与本卡无关：真造 ItemStack 会撞 Paper 的 RegistryAccess（无服务端）。
        // 用「构造拦截」把 new QuestScroll(...) 换成空壳 —— 只影响卷，不碰金额。
        scrolls = org.mockito.Mockito.mockConstruction(QuestScroll.class);
        try {
            init(dataDir);
        } catch (RuntimeException | Error e) {
            close();   // 构造失败也必须注销静态 mock，否则同线程后续用例全灭
            throw e;
        }
    }

    private void init(Path dataDir) {
        configValues.put("max-rejects", 5);
        configValues.put("cancel-penalty-publisher", 0.5);
        configValues.put("cancel-penalty-acceptor", 3.0);
        configValues.put("accept-deadline-days", 7);
        configValues.put("complete-deadline-days", 7);

        Logger logger = Logger.getLogger("SettlementHarness");

        // —— Bukkit 静态替身（已在构造入口开好）——
        data = new QuestDataManager(new File(dataDir.toFile(), "quests.yml"), logger);
        warehouse = mock(WarehouseManager.class);
        when(warehouse.load(any())).thenReturn(new HashMap<>());
        violations = mock(ViolationManager.class);
        boardManager = mock(BoardManager.class);

        economy = mock(Economy.class);
        when(economy.has(any(OfflinePlayer.class), anyDouble())).thenAnswer(i -> canAfford);
        when(economy.withdrawPlayer(any(OfflinePlayer.class), anyDouble()))
                .thenAnswer(i -> record("WITHDRAW", i.getArgument(0), i.getArgument(1)));
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble()))
                .thenAnswer(i -> record("DEPOSIT", i.getArgument(0), i.getArgument(1)));

        @SuppressWarnings("unchecked")
        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        when(registration.getProvider()).thenReturn(economy);
        ServicesManager services = mock(ServicesManager.class);
        when(services.getRegistration(Economy.class)).thenAnswer(i -> economyAvailable ? registration : null);
        bukkit.when(Bukkit::getServicesManager).thenReturn(services);

        PluginManager pluginManager = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenAnswer(i -> offlinePlayer(i.getArgument(0)));
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);

        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.getItemMeta(any(Material.class))).thenAnswer(i -> freshItemMeta());
        bukkit.when(Bukkit::getItemFactory).thenReturn(itemFactory);

        // —— Towny 静态替身：个人委托，取不到城邦 ——
        TownyAPI townyApi = mock(TownyAPI.class);
        when(townyApi.getTown(any(Player.class))).thenReturn(null);
        when(townyApi.getTown(any(java.util.UUID.class))).thenReturn(null);
        towny.when(TownyAPI::getInstance).thenReturn(townyApi);

        // —— 插件替身 ——
        config = mock(FileConfiguration.class);
        when(config.getInt(anyString(), anyInt()))
                .thenAnswer(i -> ((Number) configValues.getOrDefault(i.getArgument(0, String.class), i.getArgument(1))).intValue());
        when(config.getDouble(anyString(), anyDouble()))
                .thenAnswer(i -> ((Number) configValues.getOrDefault(i.getArgument(0, String.class), i.getArgument(1))).doubleValue());

        plugin = newPlugin(data, warehouse, violations, boardManager, config, logger);
    }

    /**
     * 插件替身：{@code org.bukkit.plugin.java.JavaPlugin} 无法被 Mockito 插桩，
     * 所以手写一个子类把用到的 getter 全部覆盖（不调 onEnable，不碰服务器）。
     */
    static final class FakePlugin extends QuestPlugin {
        private QuestDataManager data;
        private WarehouseManager warehouse;
        private ViolationManager violations;
        private BoardManager boards;
        private FileConfiguration config;
        private Logger logger;

        void init(QuestDataManager data, WarehouseManager warehouse, ViolationManager violations,
                  BoardManager boards, FileConfiguration config, Logger logger) {
            this.data = data;
            this.warehouse = warehouse;
            this.violations = violations;
            this.boards = boards;
            this.config = config;
            this.logger = logger;
        }

        @Override public QuestDataManager getDataManager() { return data; }
        @Override public WarehouseManager getWarehouseManager() { return warehouse; }
        @Override public ViolationManager getViolationManager() { return violations; }
        @Override public BoardManager getBoardManager() { return boards; }
        @Override public FileConfiguration getConfig() { return config; }
        @Override public Logger getLogger() { return logger; }
    }

    /**
     * 造插件替身：{@code JavaPlugin} 的构造函数会 {@code Bukkit.getUnsafe()} 要 lifecycle 管理器，
     * 而 {@code JavaPlugin} 本身又无法被 Mockito 插桩 —— 所以用 Objenesis 跳过构造（Mockito 自带依赖）。
     */
    private static QuestPlugin newPlugin(QuestDataManager data, WarehouseManager warehouse,
                                        ViolationManager violations, BoardManager boards,
                                        FileConfiguration config, Logger logger) {
        FakePlugin plugin = new org.objenesis.ObjenesisStd().newInstance(FakePlugin.class);
        plugin.init(data, warehouse, violations, boards, config, logger);
        setPluginMeta(plugin, "Quest");
        return plugin;
    }

    private static void setPluginMeta(JavaPlugin plugin, String name) {
        try {
            var meta = mock(io.papermc.paper.plugin.configuration.PluginMeta.class);
            when(meta.getName()).thenReturn(name);
            when(meta.namespace()).thenReturn(name.toLowerCase(java.util.Locale.ROOT));
            var field = JavaPlugin.class.getDeclaredField("pluginMeta");
            field.setAccessible(true);
            field.set(plugin, meta);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置 pluginMeta", e);
        }
    }

    // ==================== 玩家替身 ====================

    /** 造一个玩家替身：UUID 稳定、主手空、背包为空（委托卷落地即丢弃，不影响金额）。 */
    Player player(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        names.put(id, name);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        ItemStack emptyHand = mock(ItemStack.class);
        when(emptyHand.getType()).thenReturn(Material.AIR);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenReturn(emptyHand);
        when(inventory.getSize()).thenReturn(0);
        when(player.getInventory()).thenReturn(inventory);
        when(player.spigot()).thenReturn(mock(Player.Spigot.class));
        return player;
    }

    // ==================== 各条路径（真实调用生产代码） ====================

    /** 发布：聊天引导四步 + 确认发布 → 命中 {@code BoardMenuHandler.confirmPublish}。 */
    Quest publish(Player publisher, double reward, double deposit) {
        Quest quest = publishOrNull(publisher, reward, deposit);
        if (quest == null) throw new IllegalStateException("发布没有建单");
        return quest;
    }

    /** 发布（允许被守卫挡下：经济不可用 / 余额不足时不会建单），返回落盘委托或 null。 */
    Quest publishOrNull(Player publisher, double reward, double deposit) {
        BoardMenuHandler menu = new BoardMenuHandler(plugin, boardManager, data);
        menu.startMaterialCreation(publisher);
        menu.handleCreationInput(publisher, "橡木原木");
        menu.handleCreationInput(publisher, "64");
        menu.handleCreationInput(publisher, plain(reward));
        menu.handleCreationInput(publisher, plain(deposit));
        menu.confirmPublish(publisher);
        return questCount() == 0 ? null : quest();
    }

    /** 接取（告示牌路径）→ 命中 {@code BoardAcceptHandler.accept}。 */
    Quest acceptViaBoard(Player acceptor, Quest quest) {
        BoardAcceptHandler handler = new BoardAcceptHandler(plugin, boardManager,
                mock(BoardDisplayManager.class), data);
        invokePrivate(handler, "accept", new Class<?>[]{Player.class, Quest.class}, acceptor, quest);
        return quest();
    }

    /** 接取（命令路径）→ 命中 {@code /quest accept}。 */
    Quest acceptViaCommand(Player acceptor, Quest quest) {
        new QuestCommand(plugin).onCommand(acceptor, null, "quest",
                new String[]{"accept", quest.id().toString().substring(0, 8)});
        return quest();
    }

    /** 完成/批准 → 命中 {@code QuestGuiManager.handleApprove}。 */
    Quest approve(Player publisher, Quest quest) {
        invokePrivate(gui(), "handleApprove", new Class<?>[]{Player.class, Quest.class}, publisher, quest);
        return quest();
    }

    /** 驳回达上限终止 → 命中 {@code QuestGuiManager.handleTerminate}。 */
    Quest terminate(Player publisher, Quest quest) {
        invokePrivate(gui(), "handleTerminate", new Class<?>[]{Player.class, Quest.class}, publisher, quest);
        return quest();
    }

    /** 取消/撤回 → 命中 {@code QuestGuiManager.handleCancel}（取消方 = actor）。 */
    Quest cancel(Player actor, Quest quest) {
        invokePrivate(gui(), "handleCancel", new Class<?>[]{Player.class, Quest.class}, actor, quest);
        return quest();
    }

    /** 跑一次 30 秒扫描 → 命中 {@code QuestScheduler.run} 的超时结算。 */
    void expireTick() {
        new QuestScheduler(plugin).run();
    }

    private QuestGuiManager gui() {
        return new QuestGuiManager(plugin);
    }

    // ==================== 数据侧 ====================

    Quest quest() {
        List<Quest> all = data.loadAll();
        if (all.isEmpty()) throw new IllegalStateException("委托没有落盘");
        return all.get(0);
    }

    int questCount() {
        return data.loadAll().size();
    }

    /** 把委托的接取/完成期限推到过去，制造超时（不动金额，只动时间）。 */
    Quest expireAcceptDeadline(Quest q) {
        long past = System.currentTimeMillis() - 60_000L;
        Quest expired = copy(q, past, q.completeDeadline());
        data.saveAll(List.of(expired));
        return expired;
    }

    Quest expireCompleteDeadline(Quest q) {
        long past = System.currentTimeMillis() - 60_000L;
        Quest expired = copy(q, q.acceptDeadline(), past);
        data.saveAll(List.of(expired));
        return expired;
    }

    private static Quest copy(Quest q, long acceptDeadline, long completeDeadline) {
        return new Quest(q.id(), q.title(), q.publisherId(), q.publisherName(), q.isTownQuest(),
                q.questType(), q.description(), q.reward(), q.deposit(),
                acceptDeadline, completeDeadline, q.status(),
                q.acceptorId(), q.acceptorName(), q.isTownAcceptor(), q.rejectCount());
    }

    // ==================== 账本断言材料 ====================

    /** 账本按发生顺序渲染成人话：{@code "WITHDRAW 发布方 600.0"}。 */
    List<String> ledgerLines() {
        List<String> lines = new ArrayList<>();
        for (Money m : ledger) {
            lines.add(m.op() + " " + name(m.who()) + " " + String.format(java.util.Locale.ROOT, "%.1f", m.amount()));
        }
        return lines;
    }

    List<Money> ledger() {
        return List.copyOf(ledger);
    }

    /** 某人这笔路径上的净变化（入账为正、出账为负）。 */
    double net(UUID who) {
        double sum = 0;
        for (Money m : ledger) {
            if (m.who().equals(who)) sum += "DEPOSIT".equals(m.op()) ? m.amount() : -m.amount();
        }
        return sum;
    }

    /** 全系统净变化：三条「不守恒」路径会给出负数的固定缺口。 */
    double netTotal() {
        double sum = 0;
        for (Money m : ledger) sum += "DEPOSIT".equals(m.op()) ? m.amount() : -m.amount();
        return sum;
    }

    String name(UUID who) {
        return names.getOrDefault(who, who.toString());
    }

    // ==================== 开关 ====================

    /** 关掉 {@code has()}：验证「余额不足 → 一个钱都不动」。 */
    void setCanAfford(boolean value) {
        this.canAfford = value;
    }

    /** 关掉经济服务注册：验证 {@code econ == null} 时不扣款。 */
    void setEconomyAvailable(boolean value) {
        this.economyAvailable = value;
    }

    /** 改 config 值（例如罚金系数），验证系数确实是从配置读的。 */
    void setConfig(String key, Object value) {
        configValues.put(key, value);
    }

    ViolationManager violations() {
        return violations;
    }

    Economy economy() {
        return economy;
    }

    // ==================== 内部 ====================

    private EconomyResponse record(String op, Object who, Object amount) {
        UUID id = ((OfflinePlayer) who).getUniqueId();
        ledger.add(new Money(op, id, ((Number) amount).doubleValue()));
        return new EconomyResponse(((Number) amount).doubleValue(), 0,
                EconomyResponse.ResponseType.SUCCESS, "");
    }

    private OfflinePlayer offlinePlayer(UUID id) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(names.get(id));
        return player;
    }

    private static ItemMeta freshItemMeta() {
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        return meta;
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** 生产方法是 private，测试只能从这里进；调用的是<b>同一份</b>代码，不复制算式。 */
    static Object invokePrivate(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getDeclaredMethod(method, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射调用失败: " + method, e);
        }
    }

    @Override
    public void close() {
        scrolls.close();
        towny.close();
        bukkit.close();
    }
}
