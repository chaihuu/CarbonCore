/*
 * This file is part of CarbonCore.
 *
 * Copyright (C) 2026 Chaihu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package xyz.chaihu.carboncore.quest;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.io.File;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * Quest definitions, in-memory progress cache and reward granting.
 *
 * Progress is tracked per (player, quest, period) where the period string is
 * derived from the quest's reset cadence, so a new day / ISO week naturally
 * starts from zero without any cleanup task.
 */
public final class QuestService {

    /** Cached progress of one quest in the current period. */
    public record State(int progress, boolean claimed) {
    }

    private static final String STATIC_PERIOD = "static";
    private static final int BAR_CELLS = 10;

    private static final String UPSERT_SQL = """
            INSERT INTO quest_progress(uuid, quest_id, period, progress, claimed) VALUES(?,?,?,?,0)
            ON CONFLICT(uuid, quest_id, period) DO UPDATE SET progress = MIN(?, excluded.progress)""";
    private static final String CLAIM_CAS_SQL = """
            UPDATE quest_progress SET claimed = 1
            WHERE uuid = ? AND quest_id = ? AND period = ? AND claimed = 0 AND progress >= ?""";
    private static final String LOAD_SQL = """
            SELECT uuid, quest_id, period, progress, claimed FROM quest_progress
            WHERE period IN (?,?,?)""";

    private final CarbonCorePlugin plugin;
    private final Executor mainThread;

    /** All parsed quests (including disabled ones), in file order. */
    private volatile List<Quest> quests = List.of();
    /** uuid -> questId -> cached row for the current period. */
    private final Map<UUID, Map<String, Cached>> cache = new ConcurrentHashMap<>();

    private static final class Cached {
        final String period;
        final int progress;
        final boolean claimed;

        Cached(String period, int progress, boolean claimed) {
            this.period = period;
            this.progress = progress;
            this.claimed = claimed;
        }
    }

    private record ProgressRow(String uuid, String questId, String period, int progress, boolean claimed) {
    }

    public QuestService(CarbonCorePlugin plugin) {
        this.plugin = plugin;
        this.mainThread = run -> Bukkit.getScheduler().runTask(plugin, run);
    }

    // ------------------------------------------------------------------
    // Definitions
    // ------------------------------------------------------------------

    /**
     * Re-reads quests.yml, resets the cache and asynchronously reloads the
     * rows belonging to the current periods.
     *
     * @return the number of enabled quests
     */
    public int reload() {
        File file = new File(plugin.getDataFolder(), "quests.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("quests");
        List<Quest> parsed = new ArrayList<>();
        if (root != null) {
            for (String id : root.getKeys(false)) {
                Quest quest = parse(id, root.getConfigurationSection(id));
                if (quest != null) {
                    parsed.add(quest);
                }
            }
        }
        this.quests = List.copyOf(parsed);
        this.cache.clear();
        loadCache();
        int enabled = 0;
        for (Quest q : parsed) {
            if (q.enabled()) {
                enabled++;
            }
        }
        return enabled;
    }

    private Quest parse(String id, ConfigurationSection s) {
        if (s == null) {
            return null;
        }
        QuestType type;
        try {
            type = QuestType.valueOf(s.getString("type", "KILL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Quest '" + id + "' has an unknown type '"
                    + s.getString("type") + "', skipping it.");
            return null;
        }
        Quest.Reset reset;
        try {
            reset = Quest.Reset.valueOf(s.getString("reset", "none").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            reset = Quest.Reset.NONE;
        }
        String target = s.getString("target", "ANY");
        int goal = Math.max(1, s.getInt("goal", 1));
        double money = Math.max(0.0, s.getDouble("reward.money", 0.0));
        List<Quest.ItemReward> items = new ArrayList<>();
        for (Map<?, ?> entry : s.getMapList("reward.items")) {
            Object rawMaterial = entry.get("material");
            if (rawMaterial == null) {
                continue;
            }
            Material material = Material.matchMaterial(String.valueOf(rawMaterial));
            if (material == null) {
                plugin.getLogger().warning("Quest '" + id + "' has an unknown item material '"
                        + rawMaterial + "', skipping that entry.");
                continue;
            }
            Object rawAmount = entry.get("amount");
            int amount = rawAmount instanceof Number n ? Math.max(1, n.intValue()) : 1;
            items.add(new Quest.ItemReward(material, amount));
        }
        List<String> commands = new ArrayList<>(s.getStringList("reward.commands"));
        String display = s.getString("display", id);
        String description = s.getString("description", "");
        boolean enabled = s.getBoolean("enabled", true);
        return new Quest(id, enabled, type, target, goal, reset, money, items, commands, display, description);
    }

    /** Enabled quests in definition order. */
    public List<Quest> quests() {
        List<Quest> out = new ArrayList<>();
        for (Quest q : quests) {
            if (q.enabled()) {
                out.add(q);
            }
        }
        return out;
    }

    /** Finds an enabled quest by id (case-insensitive), or null. */
    public Quest findQuest(String id) {
        if (id == null) {
            return null;
        }
        for (Quest q : quests) {
            if (q.enabled() && q.id().equalsIgnoreCase(id)) {
                return q;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Periods
    // ------------------------------------------------------------------

    /** The current period key for a quest (part of the DB primary key). */
    public String periodFor(Quest quest) {
        return switch (quest.reset()) {
            case DAILY -> LocalDate.now().toString();
            case WEEKLY -> isoWeek(LocalDate.now());
            case NONE -> STATIC_PERIOD;
        };
    }

    private static String isoWeek(LocalDate date) {
        int year = date.get(WeekFields.ISO.weekBasedYear());
        int week = date.get(WeekFields.ISO.weekOfWeekBasedYear());
        return year + "-W" + String.format("%02d", week);
    }

    // ------------------------------------------------------------------
    // Cache
    // ------------------------------------------------------------------

    private void loadCache() {
        LocalDate today = LocalDate.now();
        String daily = today.toString();
        String weekly = isoWeek(today);
        plugin.database().queryList(LOAD_SQL, rs -> new ProgressRow(
                        rs.getString("uuid"),
                        rs.getString("quest_id"),
                        rs.getString("period"),
                        rs.getInt("progress"),
                        rs.getInt("claimed") == 1),
                daily, weekly, STATIC_PERIOD)
                .thenAcceptAsync(rows -> {
                    for (ProgressRow row : rows) {
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(row.uuid());
                        } catch (IllegalArgumentException ex) {
                            continue;
                        }
                        Quest quest = findQuest(row.questId());
                        if (quest == null || !row.period().equals(periodFor(quest))) {
                            continue;
                        }
                        put(uuid, quest, row.period(), row.progress(), row.claimed());
                    }
                }, mainThread)
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING, "Failed to load quest progress into the cache.", ex);
                    return null;
                });
    }

    /** Cached state for the quest's current period; stale periods read as fresh. */
    public State state(UUID uuid, Quest quest) {
        Map<String, Cached> perQuest = cache.get(uuid);
        if (perQuest == null) {
            return new State(0, false);
        }
        Cached cached = perQuest.get(quest.id());
        if (cached == null || !cached.period.equals(periodFor(quest))) {
            return new State(0, false);
        }
        return new State(cached.progress, cached.claimed);
    }

    private void put(UUID uuid, Quest quest, String period, int progress, boolean claimed) {
        cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .put(quest.id(), new Cached(period, progress, claimed));
    }

    /** Number of enabled quests whose current-period progress reached the goal. */
    public int completedCount(UUID uuid) {
        int count = 0;
        for (Quest q : quests()) {
            if (state(uuid, q).progress() >= q.goal()) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    /**
     * Advances matching quests. Must be called on the main thread (events do).
     *
     * @param targetName the concrete target of the action (entity type, material,
     *                   quest id for COMPLETE, or "ANY" when there is none)
     */
    public void advance(Player player, QuestType type, String targetName, int amount) {
        UUID uuid = player.getUniqueId();
        for (Quest q : quests()) {
            if (q.type() != type) {
                continue;
            }
            if (!q.target().equalsIgnoreCase("ANY") && !q.target().equalsIgnoreCase(targetName)) {
                continue;
            }
            String period = periodFor(q);
            State old = state(uuid, q);
            if (old.claimed()) {
                continue;
            }
            int updated = Math.min(q.goal(), old.progress() + amount);
            if (updated == old.progress()) {
                continue;
            }
            put(uuid, q, period, updated, false);
            plugin.database().update(UPSERT_SQL, uuid.toString(), q.id(), period, updated, q.goal())
                    .exceptionally(ex -> {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to persist quest progress for " + player.getName()
                                        + " / " + q.id() + ".", ex);
                        return null;
                    });
            if (updated >= q.goal()) {
                plugin.messages().sendPlayer(player, "quest.completed", "name", q.display());
            }
        }
    }

    // ------------------------------------------------------------------
    // Claiming
    // ------------------------------------------------------------------

    /**
     * Claims a completed quest's reward. A compare-and-set UPDATE guarantees
     * the reward is granted at most once even if the cache is out of date.
     */
    public void claim(Player player, String questId) {
        Quest quest = findQuest(questId);
        if (quest == null) {
            plugin.messages().sendPlayer(player, "quest.not-found", "id", questId);
            return;
        }
        UUID uuid = player.getUniqueId();
        String period = periodFor(quest);
        State st = state(uuid, quest);
        if (st.claimed()) {
            plugin.messages().sendPlayer(player, "quest.already-claimed");
            return;
        }
        if (st.progress() < quest.goal()) {
            plugin.messages().sendPlayer(player, "quest.not-complete");
            return;
        }
        // Optimistically mark claimed so a second click cannot race ahead.
        put(uuid, quest, period, st.progress(), true);
        plugin.database().update(CLAIM_CAS_SQL, uuid.toString(), quest.id(), period, quest.goal())
                .thenAcceptAsync(rows -> {
                    if (rows == 1) {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online != null && online.isOnline()) {
                            grant(online, quest);
                        } else {
                            // Player logged out mid-claim: roll the row back so the
                            // reward is not lost, they can claim again after re-login.
                            put(uuid, quest, period, st.progress(), false);
                            plugin.database().update(
                                            "UPDATE quest_progress SET claimed = 0 WHERE uuid = ? AND quest_id = ? AND period = ?",
                                            uuid.toString(), quest.id(), period)
                                    .exceptionally(ex -> {
                                        plugin.getLogger().log(Level.WARNING,
                                                "Failed to roll back quest claim for " + quest.id() + ".", ex);
                                        return null;
                                    });
                        }
                    } else {
                        // DB says the reward was already claimed elsewhere.
                        plugin.messages().sendPlayer(player, "quest.already-claimed");
                    }
                }, mainThread)
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING,
                            "Quest claim failed for " + player.getName() + " / " + quest.id() + ".", ex);
                    put(uuid, quest, period, st.progress(), false);
                    return null;
                });
    }

    /** Grants all rewards of a quest. Runs on the main thread. */
    private void grant(Player player, Quest quest) {
        if (quest.money() > 0 && plugin.economy().hasEconomy()) {
            plugin.economy().deposit(player, quest.money());
            plugin.messages().sendPlayer(player, "quest.reward-money",
                    "money", plugin.economy().format(quest.money()));
        }
        for (Quest.ItemReward reward : quest.items()) {
            int max = Math.max(1, reward.material().getMaxStackSize());
            int remaining = reward.amount();
            List<ItemStack> stacks = new ArrayList<>();
            while (remaining > 0) {
                int n = Math.min(max, remaining);
                stacks.add(new ItemStack(reward.material(), n));
                remaining -= n;
            }
            Map<Integer, ItemStack> leftover =
                    player.getInventory().addItem(stacks.toArray(new ItemStack[0]));
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            if (!leftover.isEmpty()) {
                plugin.messages().sendPlayer(player, "general.item-could-not-fit");
            }
            plugin.messages().sendPlayer(player, "quest.reward-item",
                    "name", reward.material().name(), "amount", reward.amount());
        }
        if (!quest.commands().isEmpty()) {
            plugin.messages().sendPlayer(player, "quest.reward-command");
            for (String command : quest.commands()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        command.replace("{player}", player.getName()));
            }
        }
        plugin.messages().sendPlayer(player, "quest.reward-claimed", "name", quest.display());
        // Claiming a quest counts towards COMPLETE quests.
        advance(player, QuestType.COMPLETE, quest.id(), 1);
    }

    // ------------------------------------------------------------------
    // Shared display helpers (GUI + form)
    // ------------------------------------------------------------------

    /** Colored reset-cadence label from messages.yml. */
    public String resetText(Quest quest) {
        return switch (quest.reset()) {
            case DAILY -> plugin.messages().raw("quest.reset-daily");
            case WEEKLY -> plugin.messages().raw("quest.reset-weekly");
            case NONE -> plugin.messages().raw("quest.reset-none");
        };
    }

    /** Colored state label (claimed / done / in progress) from messages.yml. */
    public String statusText(Quest quest, State state) {
        if (state.claimed()) {
            return plugin.messages().raw("quest.state-claimed");
        }
        if (state.progress() >= quest.goal()) {
            return plugin.messages().raw("quest.state-done");
        }
        return plugin.messages().raw("quest.state-progress");
    }

    /** A 10-cell text progress bar, e.g. "&a■■■■□□□□□□". */
    public String progressBar(int progress, int goal) {
        int safeGoal = Math.max(1, goal);
        int filled = (int) Math.round(BAR_CELLS * (double) Math.min(progress, safeGoal) / safeGoal);
        filled = Math.max(0, Math.min(BAR_CELLS, filled));
        return "&a" + "■".repeat(filled) + "&8" + "□".repeat(BAR_CELLS - filled);
    }
}
