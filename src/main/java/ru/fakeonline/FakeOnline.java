package ru.fakeonline;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class FakeOnline extends JavaPlugin implements Listener {

    private NPCRegistry registry;
    private final Random rnd = new Random();
    private final Map<String, NPC> active = new LinkedHashMap<>();
    private final Map<String, Long> idleUntil = new HashMap<>();
    private final Map<String, UUID> chasing = new HashMap<>();
    private final Map<String, Long> chaseUntil = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        // Ждём конца загрузки мира и Citizens
        Bukkit.getScheduler().runTaskLater(this, this::start, 40L);
    }

    @Override
    public void onDisable() {
        stop();
    }

    private void start() {
        registry = CitizensAPI.createNamedNPCRegistry("fakeonline", new MemoryNPCDataStore());

        List<String> names = new ArrayList<>(getConfig().getStringList("bots"));
        Collections.shuffle(names, rnd);
        int min = Math.min(getConfig().getInt("min-online", 10), names.size());
        int max = Math.min(getConfig().getInt("max-online", 15), names.size());
        if (max < min) max = min;
        int count = min + (max > min ? rnd.nextInt(max - min + 1) : 0);

        // боты появляются не разом, а с небольшими интервалами
        for (int i = 0; i < count; i++) {
            String n = names.get(i);
            long delay = 20L * (1 + i * 2) + rnd.nextInt(40);
            tasks.add(Bukkit.getScheduler().runTaskLater(this, () -> spawnBot(n), delay));
        }

        tasks.add(Bukkit.getScheduler().runTaskTimer(this,
                () -> new ArrayList<>(active.values()).forEach(this::tickBot), 40L, 10L));
    }

    private void stop() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (registry != null) registry.deregisterAll();
        active.clear();
        idleUntil.clear();
        chasing.clear();
        chaseUntil.clear();
        registry = null;
    }

    private void spawnBot(String name) {
        if (registry == null || active.containsKey(name)) return;
        NPC npc = registry.createNPC(EntityType.PLAYER, name);
        // оставляем бота в табе - как у настоящего игрока
        npc.data().setPersistent(NPC.Metadata.REMOVE_FROM_TABLIST, false);
        npc.spawn(randomPoint());
        active.put(name, npc);
    }

    // ---------- поведение ----------

    private void tickBot(NPC npc) {
        if (!npc.isSpawned() || !(npc.getEntity() instanceof Player p)) return;
        String name = npc.getName();
        long now = System.currentTimeMillis();

        if (getConfig().getBoolean("attack.enabled", true) && handleAttack(npc, p, name, now)) {
            return;
        }

        if (npc.getNavigator().isNavigating()) return;
        if (now < idleUntil.getOrDefault(name, 0L)) return;

        if (rnd.nextInt(100) < getConfig().getInt("pause-chance", 25)) {
            idleUntil.put(name, now + 2000L + rnd.nextInt(4000));
            return;
        }
        walkTo(npc, randomPoint(), 0.85f + rnd.nextFloat() * 0.4f);
    }

    private void walkTo(NPC npc, Location target, float speed) {
        var params = npc.getNavigator().getLocalParameters();
        params.speedModifier(speed);
        params.range(80);
        params.distanceMargin(1.5);
        npc.getNavigator().setTarget(target);
    }

    /** @return true, если бот сейчас занят погоней/ударом */
    private boolean handleAttack(NPC npc, Player p, String name, long now) {
        UUID tid = chasing.get(name);

        if (tid != null) {
            Player t = Bukkit.getPlayer(tid);
            if (t == null || !canBeHit(t) || t.getWorld() != p.getWorld()
                    || now > chaseUntil.getOrDefault(name, 0L)) {
                stopChase(npc, name);
                return false;
            }
            double hitRange = getConfig().getDouble("attack.hit-range", 3.0);
            if (p.getLocation().distance(t.getLocation()) <= hitRange) {
                npc.getNavigator().cancelNavigation();
                npc.faceLocation(t.getEyeLocation());
                p.swingMainHand();
                t.damage(getConfig().getDouble("attack.damage", 1.0), p);
                stopChase(npc, name); // один удар за заход, дальше бот идёт по своим делам
                idleUntil.put(name, now + 1500L + rnd.nextInt(2500));
            } else {
                walkTo(npc, t.getLocation(), 1.15f);
            }
            return true;
        }

        if (rnd.nextInt(1000) >= getConfig().getInt("attack.chance-permille", 8)) return false;

        double notice = getConfig().getDouble("attack.notice-range", 10);
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player t : p.getWorld().getPlayers()) {
            if (!canBeHit(t)) continue;
            double d = t.getLocation().distanceSquared(p.getLocation());
            if (d <= notice * notice && d < best) {
                best = d;
                nearest = t;
            }
        }
        if (nearest == null) return false;

        chasing.put(name, nearest.getUniqueId());
        chaseUntil.put(name, now + getConfig().getInt("attack.chase-seconds", 8) * 1000L);
        walkTo(npc, nearest.getLocation(), 1.15f);
        return true;
    }

    private boolean canBeHit(Player t) {
        return t.isOnline() && !t.isDead()
                && t.getGameMode() != GameMode.CREATIVE
                && t.getGameMode() != GameMode.SPECTATOR
                && !t.hasPermission("fakeonline.immune");
    }

    private void stopChase(NPC npc, String name) {
        chasing.remove(name);
        chaseUntil.remove(name);
        npc.getNavigator().cancelNavigation();
    }

    // ---------- точки ----------

    private World world() {
        String w = getConfig().getString("world", "");
        World world = (w == null || w.isBlank()) ? null : Bukkit.getWorld(w);
        return world != null ? world : Bukkit.getWorlds().get(0);
    }

    private Location randomPoint() {
        World w = world();
        Location c = w.getSpawnLocation();
        double radius = getConfig().getDouble("radius", 20);
        for (int i = 0; i < 15; i++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = Math.sqrt(rnd.nextDouble()) * radius;
            int x = (int) Math.floor(c.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(c.getZ() + Math.sin(angle) * dist);
            int y = w.getHighestBlockYAt(x, z);
            Block b = w.getBlockAt(x, y, z);
            if (b.isLiquid()) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return c;
    }

    // ---------- онлайн в списке серверов ----------

    @EventHandler
    public void onPing(PaperServerListPingEvent e) {
        if (getConfig().getBoolean("count-in-server-list", true)) {
            e.setNumPlayers(e.getNumPlayers() + active.size());
        }
    }

    // ---------- команда ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            stop();
            reloadConfig();
            Bukkit.getScheduler().runTaskLater(this, this::start, 20L);
            s.sendMessage("FakeOnline перезагружен.");
            return true;
        }
        return false;
    }
}
