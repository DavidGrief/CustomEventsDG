package ru.dg.customevents;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CustomEventsDG extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private boolean active = false;
    private boolean phaseOneDone = false;
    private boolean bossSpawned = false;

    private Location center;
    private long endAtMillis;
    private long nextBossSummonMillis;

    private BossBar bossBar;
    private LivingEntity boss;

    private final Set<UUID> eventMobs = new HashSet<>();
    private final Set<Location> crystalBlocks = new HashSet<>();
    private final Set<Location> activatedCrystals = new HashSet<>();
    private final List<Location> placedBlocks = new ArrayList<>();

    private BukkitRunnable mainTask;
    private BukkitRunnable autoTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginCommand cmd = getCommand("cevent");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        startMainTask();
        startAutoTask();

        banner();
    }

    @Override
    public void onDisable() {
        stopEvent(false);
        if (mainTask != null) mainTask.cancel();
        if (autoTask != null) autoTask.cancel();
    }

    private void banner() {
        getLogger().info("====================================");
        getLogger().info(" CustomEventsDG");
        getLogger().info(" Автор: Давид Grief");
        getLogger().info(" Версия: 1.0.0");
        getLogger().info("====================================");
    }

    private void startMainTask() {
        mainTask = new BukkitRunnable() {
            int particleTick = 0;

            @Override
            public void run() {
                if (!active || center == null) return;

                if (System.currentTimeMillis() >= endAtMillis) {
                    broadcast("&7Разлом закрылся, никто не успел забрать его сокровища...");
                    stopEvent(true);
                    return;
                }

                particleTick++;
                spawnZoneParticles(particleTick);
                updateBossBar();

                if (!phaseOneDone && eventMobs.isEmpty()) {
                    phaseOneDone = true;
                    broadcast("&aПервая волна уничтожена! Активируйте 3 кристалла!");
                    playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
                }

                if (bossSpawned && boss != null && !boss.isDead()) {
                    if (System.currentTimeMillis() >= nextBossSummonMillis) {
                        summonBossMinions();
                        nextBossSummonMillis = System.currentTimeMillis() + getConfig().getInt("boss.summon-minions-every-seconds", 15) * 1000L;
                    }
                }
            }
        };
        mainTask.runTaskTimer(this, 20L, 20L);
    }

    private void startAutoTask() {
        if (!getConfig().getBoolean("event.auto-start", false)) return;

        int interval = getConfig().getInt("event.interval-seconds", 7200);

        autoTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!active) {
                    startEventRandom();
                }
            }
        };
        autoTask.runTaskTimer(this, interval * 20L, interval * 20L);
    }

    private boolean startEventRandom() {
        if (active) return false;

        World world = Bukkit.getWorld(getConfig().getString("settings.world", "world"));
        if (world == null) {
            getLogger().warning("World not found in config.yml");
            return false;
        }

        Location spawn = world.getSpawnLocation();
        int min = getConfig().getInt("event.min-distance-from-spawn", 1000);
        int max = getConfig().getInt("event.max-distance-from-spawn", 5000);

        Random random = new Random();

        for (int i = 0; i < 40; i++) {
            int distance = min + random.nextInt(Math.max(1, max - min));
            double angle = random.nextDouble() * Math.PI * 2;

            int x = spawn.getBlockX() + (int) (Math.cos(angle) * distance);
            int z = spawn.getBlockZ() + (int) (Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z) + 1;

            Location loc = new Location(world, x + 0.5, y, z + 0.5);

            if (loc.getBlock().getType().isSolid()) continue;

            return startEventAt(loc);
        }

        getLogger().warning("Failed to find random event location.");
        return false;
    }

    private boolean startEventAt(Location loc) {
        if (active) return false;

        active = true;
        phaseOneDone = false;
        bossSpawned = false;
        boss = null;
        crystalBlocks.clear();
        activatedCrystals.clear();
        eventMobs.clear();
        placedBlocks.clear();

        center = loc.clone();
        int duration = getConfig().getInt("event.duration-seconds", 1200);
        endAtMillis = System.currentTimeMillis() + duration * 1000L;

        buildRiftStructure();
        spawnFirstWave();

        String coords;
        if (getConfig().getBoolean("event.announce-exact-coords", true)) {
            coords = "X: " + center.getBlockX() + " Z: " + center.getBlockZ();
        } else {
            coords = "примерно X: " + round(center.getBlockX(), 100) + " Z: " + round(center.getBlockZ(), 100);
        }

        broadcast("");
        broadcast("&#aa00ff&l[ИВЕНТ] &fВ мире открылся магический Разлом!");
        broadcast("&7Координаты: &e" + coords);
        broadcast("&eУспейте пройти ивент за " + (duration / 60) + " минут!");
        broadcast("");

        playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.2f);
        center.getWorld().strikeLightningEffect(center);

        return true;
    }

    private void buildRiftStructure() {
        World world = center.getWorld();

        // Пол 9x9.
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                Location b = center.clone().add(x, -1, z);
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    setBlock(b, Material.OBSIDIAN);
                } else if ((x + z) % 2 == 0) {
                    setBlock(b, Material.CRYING_OBSIDIAN);
                } else {
                    setBlock(b, Material.BLACKSTONE);
                }
            }
        }

        // Центральный разлом.
        setBlock(center.clone(), Material.BEACON);
        setBlock(center.clone().add(0, 1, 0), Material.END_ROD);
        setBlock(center.clone().add(0, 2, 0), Material.END_ROD);

        // Декор.
        setBlock(center.clone().add(3, 0, 3), Material.CRYING_OBSIDIAN);
        setBlock(center.clone().add(-3, 0, 3), Material.CRYING_OBSIDIAN);
        setBlock(center.clone().add(3, 0, -3), Material.CRYING_OBSIDIAN);
        setBlock(center.clone().add(-3, 0, -3), Material.CRYING_OBSIDIAN);

        // 3 кристалла для ПКМ.
        addCrystal(center.clone().add(5, 0, 0));
        addCrystal(center.clone().add(-5, 0, 0));
        addCrystal(center.clone().add(0, 0, 5));

        // Немного руин.
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2 * i / 8.0;
            Location p = center.clone().add(Math.cos(angle) * 8, 0, Math.sin(angle) * 8);
            setBlock(p, Material.POLISHED_BLACKSTONE_BRICKS);
            setBlock(p.clone().add(0, 1, 0), Material.POLISHED_BLACKSTONE_BRICKS);
            if (i % 2 == 0) setBlock(p.clone().add(0, 2, 0), Material.POLISHED_BLACKSTONE_BRICKS);
        }
    }

    private void addCrystal(Location loc) {
        setBlock(loc.clone().add(0, -1, 0), Material.OBSIDIAN);
        setBlock(loc, Material.END_ROD);
        crystalBlocks.add(blockLoc(loc));
    }

    private void setBlock(Location loc, Material mat) {
        Block block = loc.getBlock();
        if (block.getType() == Material.AIR || block.getType() == Material.CAVE_AIR || block.getType() == Material.GRASS || block.getType() == Material.TALL_GRASS) {
            block.setType(mat, false);
            placedBlocks.add(blockLoc(loc));
        }
    }

    private void spawnFirstWave() {
        int zombies = getConfig().getInt("waves.first.zombies", 8);
        int skeletons = getConfig().getInt("waves.first.skeletons", 5);
        int spiders = getConfig().getInt("waves.first.spiders", 3);

        for (int i = 0; i < zombies; i++) spawnMob(EntityType.ZOMBIE, "&5Страж Разлома", 35, 5);
        for (int i = 0; i < skeletons; i++) spawnMob(EntityType.SKELETON, "&5Лучник Разлома", 28, 4);
        for (int i = 0; i < spiders; i++) spawnMob(EntityType.SPIDER, "&5Паук Разлома", 26, 4);

        broadcast("&cСтражи Разлома пробудились!");
    }

    private void spawnCrystalWave() {
        int zombies = getConfig().getInt("waves.crystal-wave.zombies", 3);
        int skeletons = getConfig().getInt("waves.crystal-wave.skeletons", 2);

        for (int i = 0; i < zombies; i++) spawnMob(EntityType.ZOMBIE, "&dПробужденный страж", 30, 5);
        for (int i = 0; i < skeletons; i++) spawnMob(EntityType.SKELETON, "&dПробужденный лучник", 25, 4);
    }

    private LivingEntity spawnMob(EntityType type, String name, double hp, double damage) {
        Location loc = randomNear(center, 7, 12);
        Entity ent = center.getWorld().spawnEntity(loc, type);

        if (!(ent instanceof LivingEntity)) return null;
        LivingEntity mob = (LivingEntity) ent;

        mob.setCustomName(color(name));
        mob.setCustomNameVisible(true);

        if (mob.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            mob.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(hp);
            mob.setHealth(hp);
        }

        if (mob.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
            mob.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(damage);
        }

        eventMobs.add(mob.getUniqueId());
        return mob;
    }

    private void spawnBoss() {
        bossSpawned = true;

        EntityType bossType;
        try {
            bossType = EntityType.valueOf(getConfig().getString("boss.type", "WITHER_SKELETON").toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            bossType = EntityType.WITHER_SKELETON;
        }

        Entity ent = center.getWorld().spawnEntity(center.clone().add(0, 1, 0), bossType);
        if (!(ent instanceof LivingEntity)) return;

        boss = (LivingEntity) ent;

        String name = getConfig().getString("boss.name", "&#aa00ff&lХранитель Разлома");
        double hp = getConfig().getDouble("boss.health", 300.0);
        double dmg = getConfig().getDouble("boss.damage", 9.0);

        boss.setCustomName(color(name));
        boss.setCustomNameVisible(true);

        if (boss.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(hp);
            boss.setHealth(hp);
        }

        if (boss.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
            boss.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(dmg);
        }

        eventMobs.add(boss.getUniqueId());

        bossBar = Bukkit.createBossBar(color(name), BarColor.PURPLE, BarStyle.SEGMENTED_10);
        bossBar.setProgress(1.0);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(center.getWorld()) && player.getLocation().distance(center) <= 80) {
                bossBar.addPlayer(player);
            }
        }

        nextBossSummonMillis = System.currentTimeMillis() + getConfig().getInt("boss.summon-minions-every-seconds", 15) * 1000L;

        broadcast("&4&lХранитель Разлома появился!");
        playSound(center, Sound.ENTITY_WITHER_SPAWN, 1f, 1f);
        center.getWorld().strikeLightningEffect(center);
    }

    private void summonBossMinions() {
        for (int i = 0; i < 3; i++) {
            spawnMob(EntityType.ZOMBIE, "&5Прислужник Хранителя", 22, 4);
        }
        playSound(center, Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.3f);
    }

    private void updateBossBar() {
        if (bossBar == null || boss == null) return;

        if (boss.isDead()) {
            bossBar.removeAll();
            return;
        }

        double max = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null
                ? boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()
                : getConfig().getDouble("boss.health", 300.0);

        bossBar.setProgress(Math.max(0, Math.min(1, boss.getHealth() / max)));

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(center.getWorld())) {
                bossBar.removePlayer(player);
                continue;
            }

            if (player.getLocation().distance(center) <= 80) {
                bossBar.addPlayer(player);
            } else {
                bossBar.removePlayer(player);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrystalClick(PlayerInteractEvent event) {
        if (!active || center == null) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        Location loc = blockLoc(event.getClickedBlock().getLocation());
        if (!crystalBlocks.contains(loc)) return;

        event.setCancelled(true);

        if (!phaseOneDone) {
            event.getPlayer().sendMessage(color(getPrefix() + "&cСначала уничтожьте первую волну мобов!"));
            return;
        }

        if (activatedCrystals.contains(loc)) {
            event.getPlayer().sendMessage(color(getPrefix() + "&7Этот кристалл уже активирован."));
            return;
        }

        activatedCrystals.add(loc);
        loc.getBlock().setType(Material.SEA_LANTERN, false);

        broadcast("&dКристалл активирован! &7(" + activatedCrystals.size() + "/3)");
        playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
        loc.getWorld().spawnParticle(Particle.SPELL_WITCH, loc.clone().add(0.5, 1, 0.5), 80, 0.7, 0.8, 0.7, 0.05);

        spawnCrystalWave();

        if (activatedCrystals.size() >= 3 && !bossSpawned) {
            spawnBoss();
        }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();

        if (!eventMobs.contains(id)) return;

        eventMobs.remove(id);
        event.getDrops().clear();
        event.setDroppedExp(0);

        if (boss != null && id.equals(boss.getUniqueId())) {
            winEvent(event.getEntity().getLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossDamage(EntityDamageByEntityEvent event) {
        if (boss == null || !event.getEntity().getUniqueId().equals(boss.getUniqueId())) return;

        if (boss.getHealth() - event.getFinalDamage() <= boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * 0.5) {
            boss.getWorld().spawnParticle(Particle.DRAGON_BREATH, boss.getLocation().add(0, 1, 0), 50, 1, 1, 1, 0.03);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!active || center == null) return;

        Location loc = event.getBlock().getLocation();
        if (loc.getWorld().equals(center.getWorld()) && loc.distance(center) <= getConfig().getInt("event.zone-radius", 35)) {
            if (!event.getPlayer().hasPermission("customevents.admin")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(color(getPrefix() + "&cНельзя ломать блоки ивента."));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        if (!active || center == null) return;
        if (!event.getLocation().getWorld().equals(center.getWorld())) return;

        if (event.getLocation().distance(center) <= getConfig().getInt("event.zone-radius", 35)) {
            event.blockList().clear();
        }
    }

    private void winEvent(Location deathLoc) {
        broadcast("&6&lИвент завершён! &eХранитель Разлома побеждён!");
        playSound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        center.getWorld().spawnParticle(Particle.FIREWORKS_SPARK, center.clone().add(0, 2, 0), 200, 2, 2, 2, 0.1);

        spawnRewardChest(deathLoc);

        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        active = false;
        clearMobs();
    }

    private void spawnRewardChest(Location loc) {
        Location chestLoc = center.clone().add(0, 0, -3);
        chestLoc.getBlock().setType(Material.CHEST, false);

        if (!(chestLoc.getBlock().getState() instanceof Chest)) return;

        Chest chest = (Chest) chestLoc.getBlock().getState();
        Inventory inv = chest.getBlockInventory();

        Random random = new Random();

        addRandomLoot(inv, "rewards.common", 4 + random.nextInt(3));
        addRandomLoot(inv, "rewards.rare", 2 + random.nextInt(2));

        if (random.nextInt(100) < 25) {
            addRandomLoot(inv, "rewards.legendary", 1);
        }

        chest.update();

        placedBlocks.add(blockLoc(chestLoc));
    }

    private void addRandomLoot(Inventory inv, String path, int count) {
        List<String> list = getConfig().getStringList(path);
        if (list.isEmpty()) return;

        Random random = new Random();

        for (int i = 0; i < count; i++) {
            String raw = list.get(random.nextInt(list.size()));
            ItemStack item = parseItem(raw);
            if (item != null) inv.addItem(item);
        }
    }

    private ItemStack parseItem(String raw) {
        try {
            String[] split = raw.split(":");
            Material mat = Material.valueOf(split[0].toUpperCase(Locale.ROOT));
            int amount = split.length >= 2 ? Integer.parseInt(split[1]) : 1;

            ItemStack item = new ItemStack(mat, amount);

            if (mat == Material.ENCHANTED_BOOK) {
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
                meta.addStoredEnchant(Enchantment.DAMAGE_ALL, 3, true);
                item.setItemMeta(meta);
            }

            return item;
        } catch (Exception ex) {
            getLogger().warning("Invalid reward item: " + raw);
            return null;
        }
    }

    private void spawnZoneParticles(int tick) {
        World world = center.getWorld();

        world.spawnParticle(Particle.PORTAL, center.clone().add(0, 1.5, 0), 25, 1.0, 1.0, 1.0, 0.05);
        world.spawnParticle(Particle.SMOKE_NORMAL, center.clone().add(0, 0.2, 0), 10, 1.2, 0.2, 1.2, 0.02);

        double radius = 4.5;
        for (int i = 0; i < 12; i++) {
            double angle = (tick * 0.2) + (Math.PI * 2 * i / 12.0);
            Location p = center.clone().add(Math.cos(angle) * radius, 0.6, Math.sin(angle) * radius);
            world.spawnParticle(Particle.SPELL_WITCH, p, 1, 0, 0, 0, 0);
        }
    }

    private void stopEvent(boolean removeBlocks) {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        clearMobs();

        if (removeBlocks && getConfig().getBoolean("event.remove-blocks-after-event", true)) {
            removePlacedBlocks();
        }

        active = false;
        phaseOneDone = false;
        bossSpawned = false;
        boss = null;
        center = null;
        crystalBlocks.clear();
        activatedCrystals.clear();
    }

    private void clearMobs() {
        for (UUID uuid : new HashSet<>(eventMobs)) {
            Entity ent = Bukkit.getEntity(uuid);
            if (ent != null && !ent.isDead()) ent.remove();
        }
        eventMobs.clear();
    }

    private void removePlacedBlocks() {
        for (Location loc : placedBlocks) {
            if (loc.getWorld() != null) {
                loc.getBlock().setType(Material.AIR, false);
            }
        }
        placedBlocks.clear();
    }

    private Location randomNear(Location base, int min, int max) {
        Random random = new Random();
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = min + random.nextDouble() * (max - min);

        Location loc = base.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
        loc.setY(loc.getWorld().getHighestBlockYAt(loc) + 1);
        return loc;
    }

    private Location blockLoc(Location loc) {
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private int round(int value, int step) {
        return Math.round(value / (float) step) * step;
    }

    private void playSound(Location loc, Sound sound, float volume, float pitch) {
        if (loc == null || loc.getWorld() == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(loc.getWorld()) && player.getLocation().distance(loc) <= 80) {
                player.playSound(loc, sound, volume, pitch);
            }
        }
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(color(getPrefix() + msg));
    }

    private String getPrefix() {
        return getConfig().getString("settings.prefix", "&#aa00ff&lCustomEventsDG &8» &f");
    }

    private String color(String text) {
        if (text == null) return "";

        Pattern pattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(buffer, ChatColor.of("#" + hex).toString());
        }

        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("start")) {
            if (!admin(sender)) return true;

            if (args.length >= 4) {
                World world = Bukkit.getWorld(args[1]);
                if (world == null) {
                    sender.sendMessage(color(getPrefix() + "&cМир не найден."));
                    return true;
                }

                try {
                    int x = Integer.parseInt(args[2]);
                    int z = Integer.parseInt(args[3]);
                    int y = world.getHighestBlockYAt(x, z) + 1;
                    startEventAt(new Location(world, x + 0.5, y, z + 0.5));
                    return true;
                } catch (NumberFormatException ex) {
                    sender.sendMessage(color(getPrefix() + "&cИспользуй: /cevent start <world> <x> <z>"));
                    return true;
                }
            }

            if (startEventRandom()) {
                sender.sendMessage(color(getPrefix() + "&aИвент запущен."));
            } else {
                sender.sendMessage(color(getPrefix() + "&cИвент уже активен или не удалось найти место."));
            }
            return true;
        }

        if (sub.equals("stop")) {
            if (!admin(sender)) return true;

            if (!active) {
                sender.sendMessage(color(getPrefix() + "&cИвент не активен."));
                return true;
            }

            stopEvent(true);
            broadcast("&7Ивент остановлен администрацией.");
            return true;
        }

        if (sub.equals("tp")) {
            if (!admin(sender)) return true;
            if (!(sender instanceof Player)) {
                sender.sendMessage(color(getPrefix() + "&cТолько игрок."));
                return true;
            }
            if (!active || center == null) {
                sender.sendMessage(color(getPrefix() + "&cИвент не активен."));
                return true;
            }

            ((Player) sender).teleport(center.clone().add(0, 2, 0));
            sender.sendMessage(color(getPrefix() + "&aТелепорт к ивенту."));
            return true;
        }

        if (sub.equals("info")) {
            if (!active || center == null) {
                sender.sendMessage(color(getPrefix() + "&cИвент не активен."));
                return true;
            }

            long left = Math.max(0, (endAtMillis - System.currentTimeMillis()) / 1000);
            sender.sendMessage(color("&#aa00ff&lCustomEventsDG"));
            sender.sendMessage(color("&fСтатус: &aактивен"));
            sender.sendMessage(color("&fКоординаты: &e" + center.getWorld().getName() + " X:" + center.getBlockX() + " Y:" + center.getBlockY() + " Z:" + center.getBlockZ()));
            sender.sendMessage(color("&fОсталось: &e" + left + " сек."));
            sender.sendMessage(color("&fФаза 1 завершена: " + (phaseOneDone ? "&aда" : "&cнет")));
            sender.sendMessage(color("&fКристаллы: &d" + activatedCrystals.size() + "/3"));
            sender.sendMessage(color("&fБосс: " + (bossSpawned ? "&aпоявился" : "&cнет")));
            return true;
        }

        if (sub.equals("reload")) {
            if (!admin(sender)) return true;
            reloadConfig();

            if (autoTask != null) autoTask.cancel();
            startAutoTask();

            sender.sendMessage(color(getPrefix() + "&aКонфиг перезагружен."));
            return true;
        }

        sendHelp(sender);
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (!sender.hasPermission("customevents.admin")) {
            sender.sendMessage(color(getPrefix() + "&cНет прав."));
            return false;
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&#aa00ff&lCustomEventsDG &7— команды"));
        sender.sendMessage(color("&e/cevent info &7— инфа об активном ивенте"));
        sender.sendMessage(color("&e/cevent start &7— запустить на рандомных координатах"));
        sender.sendMessage(color("&e/cevent start <world> <x> <z> &7— запустить на координатах"));
        sender.sendMessage(color("&e/cevent stop &7— остановить ивент"));
        sender.sendMessage(color("&e/cevent tp &7— телепорт к ивенту"));
        sender.sendMessage(color("&e/cevent reload &7— перезагрузить конфиг"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("help", "info", "start", "stop", "tp", "reload"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            List<String> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) worlds.add(world.getName());
            return filter(worlds, args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        }
        return out;
    }
}
