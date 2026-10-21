package me.jumpeffect;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class JumpEffectPlugin extends JavaPlugin implements Listener {
    private final Set<UUID> enabledPlayers = new HashSet<>();
    private final Map<UUID, Double> trackedFallDistance = new HashMap<>();
    private final Map<UUID, Boolean> wasOnGround = new HashMap<>();
    private final Map<UUID, Long> lastEffectAt = new HashMap<>();
    private final Set<BlockDisplay> activeDisplays = ConcurrentHashMap.newKeySet();

    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("JumpEffect enabled. Loaded " + enabledPlayers.size() + " player setting(s).");
    }

    @Override
    public void onDisable() {
        saveData();
        for (BlockDisplay display : new HashSet<>(activeDisplays)) {
            if (display.isValid()) display.remove();
        }
        activeDisplays.clear();
        trackedFallDistance.clear();
        wasOnGround.clear();
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data directory.");
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        for (String raw : data.getStringList("enabled-players")) {
            try {
                enabledPlayers.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Ignoring invalid UUID in data.yml: " + raw);
            }
        }
    }

    private void saveData() {
        if (data == null || dataFile == null) return;
        data.set("enabled-players", enabledPlayers.stream().map(UUID::toString).sorted().toList());
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        wasOnGround.put(player.getUniqueId(), player.isOnGround());
        trackedFallDistance.put(player.getUniqueId(), 0.0);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        boolean currentlyGrounded = player.isOnGround();
        boolean previouslyGrounded = wasOnGround.getOrDefault(uuid, currentlyGrounded);

        if (!currentlyGrounded) {
            trackedFallDistance.merge(uuid, (double) player.getFallDistance(), Math::max);
        } else if (!previouslyGrounded) {
            double fall = Math.max(
                    trackedFallDistance.getOrDefault(uuid, 0.0),
                    (double) player.getFallDistance()
            );
            trackedFallDistance.put(uuid, 0.0);
            if (enabledPlayers.contains(uuid)
                    && fall >= getConfig().getDouble("minimum-fall-distance", 50.0)) {
                triggerWithCooldown(player, fall);
            }
        } else {
            trackedFallDistance.put(uuid, 0.0);
        }
        wasOnGround.put(uuid, currentlyGrounded);
    }

    private void triggerWithCooldown(Player player, double fallDistance) {
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, getConfig().getLong("cooldown-seconds", 2L)) * 1000L;
        long last = lastEffectAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldownMs) return;
        lastEffectAt.put(player.getUniqueId(), now);
        spawnBurst(player, fallDistance);
    }

    private void spawnBurst(Player player, double fallDistance) {
        Location playerLoc = player.getLocation();
        World world = player.getWorld();

        // Scale the cosmetic effect linearly from the 50-block baseline to the 200-block target.
        // Beyond 200 blocks the values stay capped, avoiding unbounded entity counts and searches.
        double minimumFall = getConfig().getDouble("minimum-fall-distance", 50.0);
        double scaleMaxFall = Math.max(minimumFall + 1.0,
                getConfig().getDouble("scale-max-fall-distance", 200.0));
        double progress = Math.max(0.0, Math.min(1.0,
                (fallDistance - minimumFall) / (scaleMaxFall - minimumFall)));

        int baseCount = Math.max(1, getConfig().getInt("base-blocks-count", 10));
        int maxCount = Math.max(baseCount, getConfig().getInt("max-blocks-count", 30));
        int count = (int) Math.round(baseCount + (maxCount - baseCount) * progress);
        count = Math.max(1, Math.min(80, count));

        int baseRadius = Math.max(1, getConfig().getInt("base-search-radius", 4));
        int maxRadius = Math.max(baseRadius, getConfig().getInt("max-search-radius", 15));
        int radius = (int) Math.round(baseRadius + (maxRadius - baseRadius) * progress);
        radius = Math.max(1, Math.min(20, radius));

        double baseHeight = Math.max(0.2, getConfig().getDouble("base-launch-height", 2.0));
        double maxHeight = Math.max(baseHeight, getConfig().getDouble("max-launch-height", 6.0));
        double height = baseHeight + (maxHeight - baseHeight) * progress;
        height = Math.max(0.2, Math.min(12.0, height));

        int duration = Math.max(6, Math.min(100, getConfig().getInt("animation-duration", 30)));

        int centerX = playerLoc.getBlockX();
        int centerY = playerLoc.getBlockY() - 1;
        int centerZ = playerLoc.getBlockZ();

        List<Block> candidates = new ArrayList<>();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (x == centerX && z == centerZ) continue;
                if ((x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ) > radius * radius) continue;

                // Find the top solid block at or just below the player's landing level.
                for (int y = centerY + 1; y >= centerY - 2; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isSolid() && !block.isLiquid()
                            && block.getType().getHardness() >= 0
                            && block.getState() instanceof org.bukkit.block.BlockState
                            && block.getType() != Material.BARRIER) {
                        candidates.add(block);
                        break;
                    }
                }
            }
        }

        Collections.shuffle(candidates);
        if (candidates.size() > count) candidates = new ArrayList<>(candidates.subList(0, count));
        if (candidates.isEmpty()) {
            world.spawnParticle(Particle.CLOUD, playerLoc, 18, 0.7, 0.15, 0.7, 0.08);
            return;
        }

        if (getConfig().getBoolean("sound-enabled", true)) {
            world.playSound(playerLoc, Sound.BLOCK_STONE_BREAK, 0.8f, 0.65f);
        }
        world.spawnParticle(Particle.CLOUD, playerLoc, 24, 0.8, 0.15, 0.8, 0.09);

        List<AnimatedBlock> animations = new ArrayList<>();
        Random random = new Random();

        for (Block source : candidates) {
            Location origin = source.getLocation().add(0.0, 0.03, 0.0);
            BlockDisplay display = world.spawn(origin, BlockDisplay.class, entity -> {
                entity.setBlock(source.getBlockData());
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setPersistent(false);
                entity.setSilent(true);
                entity.setViewRange(1.0f);
                entity.setShadowRadius(0.0f);
                entity.setInterpolationDuration(1);
                entity.setInterpolationDelay(0);
                entity.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(0, 0, 1, 0),
                        new Vector3f(1, 1, 1),
                        new AxisAngle4f(0, 0, 1, 0)
                ));
            });
            activeDisplays.add(display);
            double peak = height * (0.65 + random.nextDouble() * 0.7);
            int delay = random.nextInt(Math.max(1, duration / 5));
            animations.add(new AnimatedBlock(display, origin.clone(), peak, delay, duration,
                    random.nextDouble() * Math.PI * 2, (random.nextDouble() - 0.5) * 0.12));
        }

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                boolean allDone = true;
                for (AnimatedBlock animation : animations) {
                    if (!animation.display.isValid()) {
                        activeDisplays.remove(animation.display);
                        continue;
                    }
                    int localTick = tick - animation.delay;
                    if (localTick < 0) {
                        allDone = false;
                        continue;
                    }
                    if (localTick >= animation.duration) {
                        animation.display.remove();
                        activeDisplays.remove(animation.display);
                        continue;
                    }
                    allDone = false;
                    double progress = localTick / (double) animation.duration;
                    // Smooth arc: starts and ends at ground level, peaks halfway through.
                    double y = animation.peakHeight * Math.sin(Math.PI * progress);
                    double side = Math.sin(progress * Math.PI) * animation.sideDrift;
                    Location next = animation.origin.clone().add(
                            Math.cos(animation.angle) * side,
                            y,
                            Math.sin(animation.angle) * side
                    );
                    animation.display.teleport(next);
                }
                if (allDone || tick > duration + duration / 5 + 2) cancel();
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private record AnimatedBlock(
            BlockDisplay display,
            Location origin,
            double peakHeight,
            int delay,
            int duration,
            double angle,
            double sideDrift
    ) {}

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("jumpeffect.admin")) {
            sender.sendMessage("§cУ тебя нет разрешения jumpeffect.admin.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            if (enabledPlayers.isEmpty()) {
                sender.sendMessage("§eJumpEffect: сейчас ни у кого не включён.");
            } else {
                sender.sendMessage("§aJumpEffect включён у §f" + enabledPlayers.size() + " §aигрок(ов):");
                for (UUID uuid : enabledPlayers) {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                    String name = offline.getName() == null ? uuid.toString() : offline.getName();
                    sender.sendMessage(" §7- §f" + name + (offline.isOnline() ? " §a(в сети)" : " §8(не в сети)"));
                }
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§aJumpEffect: настройки config.yml перезагружены.");
            return true;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            toggleNamedPlayer(sender, args[1], args[0].equalsIgnoreCase("on"));
            return true;
        }

        if (args.length == 1) {
            toggleNamedPlayer(sender, args[0], null);
            return true;
        }

        sendHelp(sender, label);
        return true;
    }

    private void toggleNamedPlayer(CommandSender sender, String name, Boolean desiredState) {
        Player online = Bukkit.getPlayerExact(name);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(name);
        UUID uuid = target.getUniqueId();
        boolean currentlyEnabled = enabledPlayers.contains(uuid);
        boolean newState = desiredState == null ? !currentlyEnabled : desiredState;

        if (newState) enabledPlayers.add(uuid);
        else enabledPlayers.remove(uuid);

        saveData();
        String displayName = target.getName() == null ? name : target.getName();
        if (newState == currentlyEnabled) {
            sender.sendMessage("§eJumpEffect уже " + (newState ? "включён" : "выключен") + " для §f" + displayName + "§e.");
        } else {
            sender.sendMessage("§aJumpEffect " + (newState ? "включён" : "выключен") + " для §f" + displayName + "§a.");
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6§lJumpEffect §7— команды");
        sender.sendMessage("§e/" + label + " <ник> §7— переключить эффект игроку");
        sender.sendMessage("§e/" + label + " on <ник> §7— включить");
        sender.sendMessage("§e/" + label + " off <ник> §7— выключить");
        sender.sendMessage("§e/" + label + " status §7— список включённых игроков");
        sender.sendMessage("§e/" + label + " reload §7— перезагрузить настройки");
        sender.sendMessage("§e/" + label + " help §7— эта подсказка");
    }
}
