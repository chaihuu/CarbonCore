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
package xyz.chaihu.carboncore.bank;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Command entry for the bank module: opens the menu without arguments and
 * exposes deposit / withdraw / transfer / balance plus the admin adjustments.
 */
public final class BankCommand implements CommandExecutor, TabCompleter {

    private final CarbonCorePlugin plugin;
    private final BankModule module;

    public BankCommand(CarbonCorePlugin plugin, BankModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    private Executor main() {
        return run -> Bukkit.getScheduler().runTask(plugin, run);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.only-player");
            return true;
        }
        if (!player.hasPermission("carboncore.bank")) {
            plugin.messages().sendPlayer(player, "general.no-permission");
            return true;
        }
        UUID uuid = player.getUniqueId();
        int cooldown = plugin.configManager().moduleCooldown("bank");
        if (!plugin.cooldowns().tryUse(uuid, "bank:cmd", cooldown)) {
            plugin.messages().sendPlayer(player, "general.cooldown",
                    "seconds", plugin.cooldowns().remaining(uuid, "bank:cmd", cooldown));
            return true;
        }
        if (args.length == 0) {
            if (!player.hasPermission("carboncore.bank.menu")) {
                plugin.messages().sendPlayer(player, "general.no-permission");
                return true;
            }
            module.openMainMenu(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "deposit", "dep" -> handleDeposit(player, args);
            case "withdraw", "wd" -> handleWithdraw(player, args);
            case "transfer", "tr" -> handleTransfer(player, args);
            case "balance", "bal" -> handleBalance(player);
            case "loan" -> handleLoan(player, args);
            case "set" -> handleAdminSet(player, args);
            case "give" -> handleAdminGive(player, args);
            case "clear" -> handleAdminClear(player, args);
            default -> plugin.messages().sendPlayer(player, "general.unknown-subcommand", "input", args[0]);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // sub commands
    // ------------------------------------------------------------------

    private void handleDeposit(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.bank.deposit")) {
            return;
        }
        if (args.length < 2) {
            usage(player, "/bank deposit <amount|all>");
            return;
        }
        double amount;
        if (args[1].equalsIgnoreCase("all")) {
            amount = plugin.economy().getBalance(player);
        } else {
            amount = parseAmount(player, args[1]);
            if (Double.isNaN(amount)) {
                return;
            }
            if (!checkRange(player, amount)) {
                return;
            }
        }
        module.service().deposit(player, amount);
    }

    private void handleWithdraw(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.bank.withdraw")) {
            return;
        }
        if (args.length < 2) {
            usage(player, "/bank withdraw <amount|all>");
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            module.service().withdrawAll(player, null);
            return;
        }
        double amount = parseAmount(player, args[1]);
        if (Double.isNaN(amount)) {
            return;
        }
        if (!checkRange(player, amount)) {
            return;
        }
        module.service().withdraw(player, amount);
    }

    private void handleTransfer(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.bank.transfer")) {
            return;
        }
        if (args.length < 3) {
            usage(player, "/bank transfer <player> <amount|all>");
            return;
        }
        String toName = args[1];
        if (args[2].equalsIgnoreCase("all")) {
            OfflinePlayer target = module.service().findPlayer(toName);
            if (target == null) {
                plugin.messages().sendPlayer(player, "general.player-not-found", "player", toName);
                return;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                plugin.messages().sendPlayer(player, "bank.cant-transfer-self");
                return;
            }
            String name = target.getName() != null ? target.getName() : toName;
            module.service().transferAll(player, target.getUniqueId(), name, null);
            return;
        }
        double amount = parseAmount(player, args[2]);
        if (Double.isNaN(amount)) {
            return;
        }
        if (!checkRange(player, amount)) {
            return;
        }
        module.service().transfer(player, toName, amount);
    }

    private void handleBalance(Player player) {
        if (!checkPermission(player, "carboncore.bank.balance")) {
            return;
        }
        plugin.messages().sendPlayer(player, "bank.balance",
                "balance", plugin.economy().format(plugin.economy().getBalance(player)));
        plugin.messages().sendPlayer(player, "bank.bank-balance",
                "balance", plugin.economy().format(module.service().cachedBankBalance(player.getUniqueId())));
    }

    private void handleLoan(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.bank.loan")) {
            return;
        }
        LoanService loans = module.loans();
        if (args.length < 2) {
            usage(player, "/bank loan <apply|repay|info|score|history>");
            return;
        }
        UUID uuid = player.getUniqueId();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "apply" -> {
                if (args.length < 3) {
                    usage(player, "/bank loan apply <amount>");
                    return;
                }
                double amount = parseAmount(player, args[2]);
                if (Double.isNaN(amount)) {
                    return;
                }
                loans.apply(player, amount, null);
            }
            case "repay" -> {
                if (args.length < 3) {
                    usage(player, "/bank loan repay <amount|all>");
                    return;
                }
                if (args[2].equalsIgnoreCase("all")) {
                    loans.repayAll(player, null);
                    return;
                }
                double amount = parseAmount(player, args[2]);
                if (Double.isNaN(amount)) {
                    return;
                }
                loans.repay(player, amount, null);
            }
            case "info" -> {
                int score = loans.cachedCreditScore(uuid);
                plugin.messages().sendPlayer(player, "bank.loan.score-info", "score", score);
                plugin.messages().sendPlayer(player, "bank.loan.credit-line",
                        "limit", plugin.economy().format(loans.creditLimit(score)));
                plugin.messages().sendPlayer(player, "bank.loan.interest-line",
                        "rate", String.format("%.2f", loans.creditInterest(score)));
                plugin.messages().sendPlayer(player, "bank.loan.debt-line",
                        "debt", plugin.economy().format(loans.cachedDebt(uuid)));
            }
            case "score" -> plugin.messages().sendPlayer(player, "bank.loan.score-info",
                    "score", loans.cachedCreditScore(uuid));
            case "history" -> loans.history(uuid, 10).thenAcceptAsync(rows -> {
                if (!player.isOnline()) {
                    return;
                }
                if (rows.isEmpty()) {
                    plugin.messages().sendPlayer(player, "general.empty");
                    return;
                }
                for (Loan loan : rows) {
                    plugin.messages().sendPlayer(player, "bank.loan.history-line",
                            "id", loan.id(),
                            "principal", plugin.economy().format(loan.principal()),
                            "status", LoanMenu.statusName(plugin, loan.status()));
                }
            }, main());
            default -> plugin.messages().sendPlayer(player, "general.unknown-subcommand", "input", args[1]);
        }
    }

    private void handleAdminSet(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.bank.admin.set")) {
            return;
        }
        if (args.length < 3) {
            usage(player, "/bank set <player> <amount>");
            return;
        }
        OfflinePlayer target = resolveTarget(player, args[1]);
        if (target == null) {
            return;
        }
        double amount = parseAmount(player, args[2]);
        if (Double.isNaN(amount)) {
            return;
        }
        if (amount < 0) {
            plugin.messages().sendPlayer(player, "general.invalid-amount", "input", args[2]);
            return;
        }
        double max = plugin.configManager().getDouble("modules.bank.max-amount", 1000000.0);
        if (amount > max) {
            plugin.messages().sendPlayer(player, "general.amount-too-high",
                    "max", plugin.economy().format(max));
            return;
        }
        String name = target.getName() != null ? target.getName() : args[1];
        module.service().adminSet(target.getUniqueId(), name, amount)
                .thenAcceptAsync(bal -> {
                    if (player.isOnline()) {
                        plugin.messages().sendPlayer(player, "bank.admin-set-success",
                                "player", name, "amount", plugin.economy().format(amount));
                    }
                }, main());
    }

    private void handleAdminGive(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.bank.admin.give")) {
            return;
        }
        if (args.length < 3) {
            usage(player, "/bank give <player> <amount>");
            return;
        }
        OfflinePlayer target = resolveTarget(player, args[1]);
        if (target == null) {
            return;
        }
        double delta = parseAmount(player, args[2]);
        if (Double.isNaN(delta)) {
            return;
        }
        if (delta == 0) {
            plugin.messages().sendPlayer(player, "general.invalid-amount", "input", args[2]);
            return;
        }
        if (Math.abs(delta) > plugin.configManager().getDouble("modules.bank.max-amount", 1000000.0)) {
            plugin.messages().sendPlayer(player, "general.amount-too-high",
                    "max", plugin.economy().format(plugin.configManager().getDouble("modules.bank.max-amount", 1000000.0)));
            return;
        }
        String name = target.getName() != null ? target.getName() : args[1];
        module.service().adminGive(target.getUniqueId(), name, delta)
                .thenAcceptAsync(bal -> {
                    if (player.isOnline()) {
                        plugin.messages().sendPlayer(player, "bank.admin-give-success",
                                "player", name,
                                "amount", plugin.economy().format(delta),
                                "balance", plugin.economy().format(bal));
                    }
                }, main());
    }

    private void handleAdminClear(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.bank.admin.clear")) {
            return;
        }
        if (args.length < 2) {
            usage(player, "/bank clear <player>");
            return;
        }
        OfflinePlayer target = resolveTarget(player, args[1]);
        if (target == null) {
            return;
        }
        String name = target.getName() != null ? target.getName() : args[1];
        module.service().adminClear(target.getUniqueId(), name)
                .thenAcceptAsync(bal -> {
                    if (player.isOnline()) {
                        plugin.messages().sendPlayer(player, "bank.admin-clear-success", "player", name);
                    }
                }, main());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private boolean checkPermission(Player player, String node) {
        if (!player.hasPermission(node)) {
            plugin.messages().sendPlayer(player, "general.no-permission");
            return false;
        }
        return true;
    }

    private void usage(Player player, String usage) {
        plugin.messages().sendPlayer(player, "general.usage", "usage", usage);
    }

    private double parseAmount(Player player, String input) {
        try {
            return BankService.round2(Double.parseDouble(input));
        } catch (NumberFormatException ex) {
            plugin.messages().sendPlayer(player, "general.invalid-amount", "input", input);
            return Double.NaN;
        }
    }

    private boolean checkRange(Player player, double amount) {
        double min = plugin.configManager().getDouble("modules.bank.min-amount", 0.01);
        double max = plugin.configManager().getDouble("modules.bank.max-amount", 1000000.0);
        if (amount < min) {
            plugin.messages().sendPlayer(player, "general.amount-too-low", "min", plugin.economy().format(min));
            return false;
        }
        if (amount > max) {
            plugin.messages().sendPlayer(player, "general.amount-too-high", "max", plugin.economy().format(max));
            return false;
        }
        return true;
    }

    private OfflinePlayer resolveTarget(Player player, String name) {
        OfflinePlayer target = module.service().findPlayer(name);
        if (target == null) {
            plugin.messages().sendPlayer(player, "general.player-not-found", "player", name);
        }
        return target;
    }

    // ------------------------------------------------------------------
    // tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (!player.hasPermission("carboncore.bank")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("deposit", "withdraw", "transfer", "balance"));
            if (player.hasPermission("carboncore.bank.loan")) {
                subs.add("loan");
            }
            if (player.hasPermission("carboncore.bank.admin.set")) {
                subs.add("set");
            }
            if (player.hasPermission("carboncore.bank.admin.give")) {
                subs.add("give");
            }
            if (player.hasPermission("carboncore.bank.admin.clear")) {
                subs.add("clear");
            }
            return filter(subs, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "transfer", "tr", "set", "give", "clear" -> {
                    return onlineNames(args[1]);
                }
                case "deposit", "dep", "withdraw", "wd" -> {
                    return filter(List.of("all"), args[1]);
                }
                case "loan" -> {
                    return filter(List.of("apply", "repay", "info", "score", "history"), args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (args.length == 3 && (sub.equals("transfer") || sub.equals("tr"))) {
            return filter(List.of("all"), args[2]);
        }
        if (args.length == 3 && sub.equals("loan") && args[1].equalsIgnoreCase("repay")) {
            return filter(List.of("all"), args[2]);
        }
        return List.of();
    }

    private List<String> onlineNames(String prefix) {
        String low = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(low)) {
                names.add(p.getName());
            }
        }
        return names;
    }

    private List<String> filter(List<String> options, String prefix) {
        String low = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(low)) {
                out.add(option);
            }
        }
        return out;
    }
}
