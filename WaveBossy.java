package pl.wavebossy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

public class WaveBossy extends JavaPlugin implements Listener {

    private static final String[] ATTACKS = {"SLAM", "LIGHTNING", "SUMMON"};
    private static final String[] ANIMS = {"RING", "PILLAR", "NONE"};
    private static final String[] SC = {"ᴀ","ʙ","ᴄ","ᴅ","ᴇ","ꜰ","ɢ","ʜ","ɪ","ᴊ","ᴋ","ʟ","ᴍ","ɴ","ᴏ","ᴘ","ǫ","ʀ","ѕ","ᴛ","ᴜ","ᴠ","ᴡ","x","ʏ","ᴢ"};

    private static class Boss {
        final String id;
        String name;
        double hp = 300, armor = 0;
        String attack = "SLAM", anim = "RING";
        boolean bar = true, board = true;
        int respawn = 1;
        Location loc; // ostatnie miejsce spawnu (nie zapisuje się do pliku)
        Boss(String id, String name) { this.id = id; this.name = name; }
    }

    private final Map<String, Boss> bosses = new LinkedHashMap<>();
    private final Map<String, LivingEntity> alive = new HashMap<>();
    private final Map<String, BossBar> bars = new HashMap<>();
    private final Map<String, Integer> cooldown = new HashMap<>();
    private final Map<UUID, String> openGui = new HashMap<>();
    private final Map<UUID, String> editing = new HashMap<>();
    private final Map<UUID, Location> signPos = new HashMap<>();
    private final Set<UUID> creating = new HashSet<>();
    private final Random random = new Random();
    private File dataFile;

    // ===================== START / STOP =====================

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "bosses.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("wavebossy").setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("WaveBossy wlaczony.");
    }

    @Override
    public void onDisable() {
        for (String id : new ArrayList<>(bars.keySet())) removeBar(id);
    }

    // ===================== KOMENDY =====================

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Komenda tylko dla graczy.");
            return true;
        }
        if (a.length == 0) {
            p.sendMessage(pref() + "/wavebossy stworz | edytuj <id> | spawn <id> | lista");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "stworz" -> openCreate(p);
            case "edytuj" -> {
                if (a.length < 2 || !bosses.containsKey(a[1])) p.sendMessage(pref() + "Nie ma takiego bossa.");
                else openEditor(p, a[1]);
            }
            case "spawn" -> {
                Boss b = a.length < 2 ? null : bosses.get(a[1]);
                if (b == null) p.sendMessage(pref() + "Nie ma takiego bossa.");
                else spawnBoss(b, frontLoc(p));
            }
            case "lista" -> openList(p);
            default -> p.sendMessage(pref() + "Nieznana podkomenda.");
        }
        return true;
    }

    // ===================== GUI: LISTA BOSSÓW =====================

    private void openList(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(sc("Lista bossów")));
        fill(inv);
        int i = 0;
        for (Boss b : bosses.values()) {
            if (i >= 45) break;
            boolean up = alive.containsKey(b.id);
            ItemStack it = item(iconFor(b.attack), sc(b.name),
                    "&8ID: &f" + b.id,
                    "&cZdrowie: &f" + (int) b.hp + "  &7Zbroja: &f" + (int) b.armor,
                    "&6Atak: &f" + b.attack,
                    "&bAnimacja: &f" + b.anim,
                    "&eRespawn: &f" + b.respawn + "h",
                    "&7BossBar: " + (b.bar ? "&awłączony" : "&cwyłączony")
                            + "  &7Scoreboard: " + (b.board ? "&awłączony" : "&cwyłączony"),
                    "",
                    up ? "&a● Żywy" : "&8● Martwy",
                    "",
                    "&7Lewy klik: &fedytuj",
                    "&7Prawy klik: &fprzywołaj");
            ItemMeta m = it.getItemMeta();
            m.setEnchantmentGlintOverride(up);
            it.setItemMeta(m);
            inv.setItem(i++, it);
        }
        if (bosses.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, sc("Brak bossów"), "Kliknij zieloną gwiazdę na dole"));
        }
        inv.setItem(49, item(Material.NETHER_STAR, sc("Stwórz nowego bossa"), "Kliknij, aby otworzyć kreator"));
        p.openInventory(inv);
        openGui.put(p.getUniqueId(), "list");
    }

    private static Material iconFor(String attack) {
        return switch (attack) {
            case "LIGHTNING" -> Material.TRIDENT;
            case "SUMMON" -> Material.ZOMBIE_HEAD;
            default -> Material.MACE;
        };
    }

    // ===================== GUI: KREATOR =====================

    private void openCreate(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(sc("Stwórz bossa")));
        fill(inv);
        inv.setItem(13, item(Material.NETHER_STAR, sc("Stwórz nowego bossa"),
                "Kliknij, aby wpisać nazwę bossa", "na tabliczce"));
        p.openInventory(inv);
        openGui.put(p.getUniqueId(), "create");
    }

    private void startSign(Player p) {
        Location loc = p.getLocation().getBlock().getLocation().add(0, 3, 0);
        Block block = loc.getBlock();
        block.setType(Material.OAK_SIGN, false);
        Sign sign = (Sign) block.getState();
        Side front = Side.FRONT;
        sign.getSide(front).line(0, Component.text("↓↓↓↓↓"));
        sign.getSide(front).line(2, Component.text("↑↑↑↑↑"));
        sign.getSide(front).line(3, Component.text("Wpisz tutaj nazwe bossa"));
        sign.update(false, false);
        creating.add(p.getUniqueId());
        signPos.put(p.getUniqueId(), loc);
        p.openSign(sign, front);
    }

    @EventHandler
    public void onSign(SignChangeEvent e) {
        Player p = e.getPlayer();
        if (!creating.remove(p.getUniqueId())) return;
        Location loc = signPos.remove(p.getUniqueId());
        if (loc != null) Bukkit.getScheduler().runTask(this, () -> loc.getBlock().setType(Material.AIR, false));

        String name = PlainTextComponentSerializer.plainText().serialize(e.line(1)).trim();
        if (name.isEmpty()) {
            p.sendMessage(pref() + "Nie wpisałeś nazwy bossa.");
            return;
        }
        String id = name.toLowerCase().replace(' ', '-');
        if (bosses.containsKey(id)) {
            p.sendMessage(pref() + "Boss o takiej nazwie już istnieje.");
            return;
        }
        bosses.put(id, new Boss(id, name));
        save();
        p.sendMessage(pref() + "Utworzono bossa §e" + sc(name) + "§r.");
        Bukkit.getScheduler().runTask(this, () -> openEditor(p, id));
    }

    // ===================== GUI: EDYTOR =====================

    private void openEditor(Player p, String id) {
        Boss b = bosses.get(id);
        if (b == null) return;
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(sc("Edytor bossa")));
        fill(inv);
        inv.setItem(4, item(Material.BOOK, sc("Boss: " + b.name), "ID: " + b.id));
        inv.setItem(8, item(Material.BLAZE_POWDER, sc("Przywołaj bossa"), "Kliknij, aby przywołać", "przed sobą"));
        inv.setItem(10, item(Material.REDSTONE, sc("Zdrowie: " + (int) b.hp), "Lewy: +100  |  Prawy: -100"));
        inv.setItem(12, item(Material.IRON_CHESTPLATE, sc("Zbroja: " + (int) b.armor), "Lewy: +1  |  Prawy: -1"));
        inv.setItem(14, item(Material.DIAMOND_SWORD, sc("Atak: " + b.attack), "Kliknij, aby zmienić atak"));
        inv.setItem(16, item(Material.FIREWORK_ROCKET, sc("Animacja: " + b.anim), "Kliknij, aby zmienić animację", "spawnu"));
        inv.setItem(20, item(b.bar ? Material.LIME_DYE : Material.GRAY_DYE,
                sc("BossBar: " + (b.bar ? "włączony" : "wyłączony")), "Kliknij, aby przełączyć"));
        inv.setItem(22, item(b.board ? Material.LIME_DYE : Material.GRAY_DYE,
                sc("Scoreboard: " + (b.board ? "włączony" : "wyłączony")), "Kliknij, aby przełączyć"));
        inv.setItem(24, item(Material.CLOCK, sc("Respawn: " + b.respawn + "h"), "Lewy: +1h  |  Prawy: -1h"));
        inv.setItem(18, item(Material.ARROW, sc("Wróć do listy"), "Kliknij, aby wrócić do listy bossów"));
        inv.setItem(26, item(Material.BARRIER, sc("Usuń bossa"), "Kliknij, aby usunąć bossa"));

        editing.put(p.getUniqueId(), id);
        p.openInventory(inv);
        openGui.put(p.getUniqueId(), "editor");
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String gui = openGui.get(p.getUniqueId());
        if (gui == null) return;
        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;
        boolean right = e.isRightClick();

        if (gui.equals("list")) {
            if (slot == 49) {
                openCreate(p);
                return;
            }
            List<Boss> list = new ArrayList<>(bosses.values());
            if (slot < 0 || slot >= list.size() || slot >= 45) return;
            Boss b = list.get(slot);
            if (right) {
                if (alive.containsKey(b.id)) {
                    p.sendMessage(pref() + "Boss §e" + sc(b.name) + "§r już żyje.");
                } else {
                    p.closeInventory();
                    spawnBoss(b, frontLoc(p));
                    p.sendMessage(pref() + "Przywołano bossa §e" + sc(b.name) + "§r.");
                }
            } else {
                openEditor(p, b.id);
            }
            return;
        }

        if (gui.equals("create")) {
            if (slot == 13) {
                p.closeInventory();
                Bukkit.getScheduler().runTask(this, () -> startSign(p));
            }
        } else if (gui.equals("editor")) {
            String id = editing.get(p.getUniqueId());
            if (id != null) editorClick(p, id, slot, right);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        openGui.remove(e.getPlayer().getUniqueId());
    }

    private void editorClick(Player p, String id, int slot, boolean right) {
        Boss b = bosses.get(id);
        if (b == null) return;
        switch (slot) {
            case 8 -> {
                p.closeInventory();
                spawnBoss(b, frontLoc(p));
                return;
            }
            case 10 -> b.hp = clamp(b.hp + (right ? -100 : 100), 50, 100000);
            case 12 -> b.armor = clamp(b.armor + (right ? -1 : 1), 0, 30);
            case 14 -> b.attack = next(ATTACKS, b.attack);
            case 16 -> b.anim = next(ANIMS, b.anim);
            case 20 -> {
                b.bar = !b.bar;
                if (!b.bar) removeBar(b.id);
            }
            case 18 -> {
                openList(p);
                return;
            }
            case 22 -> b.board = !b.board;
            case 24 -> b.respawn = (int) clamp(b.respawn + (right ? -1 : 1), 1, 72);
            case 26 -> {
                removeBoss(b);
                p.closeInventory();
                p.sendMessage(pref() + "Usunięto bossa.");
                return;
            }
            default -> { return; }
        }
        save();
        openEditor(p, id);
    }

    // ===================== SPAWN + ANIMACJE =====================

    private void spawnBoss(Boss b, Location loc) {
        if (alive.containsKey(b.id)) return;
        b.loc = loc;
        World w = loc.getWorld();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t < 40) {
                    if (b.anim.equals("RING")) {
                        double r = 4.0 * (1 - t / 40.0) + 0.5;
                        for (int i = 0; i < 36; i++) {
                            double a = i * Math.PI / 18;
                            w.spawnParticle(Particle.FLAME, loc.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), 1, 0, 0, 0, 0);
                        }
                    } else if (b.anim.equals("PILLAR")) {
                        w.spawnParticle(Particle.END_ROD, loc.clone().add(0, t * 0.1, 0), 6, 0.3, 0.1, 0.3, 0.02);
                    }
                    t++;
                    return;
                }
                cancel();
                Zombie z = (Zombie) w.spawnEntity(loc, EntityType.ZOMBIE);
                z.customName(Component.text("☠ " + sc(b.name)).color(NamedTextColor.RED));
                z.setCustomNameVisible(true);
                z.setPersistent(true);
                z.setRemoveWhenFarAway(false);
                z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(b.hp);
                z.setHealth(b.hp);
                z.getAttribute(Attribute.ARMOR).setBaseValue(b.armor);
                alive.put(b.id, z);
                cooldown.put(b.id, 0);
                if (b.bar) bars.put(b.id, Bukkit.createBossBar(sc(b.name), BarColor.RED, BarStyle.SEGMENTED_10));
                w.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 1f, 1f);
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    // ===================== PĘTLA (co sekundę) =====================

    private void tick() {
        for (Boss b : new ArrayList<>(bosses.values())) {
            LivingEntity z = alive.get(b.id);
            if (z == null) continue;
            if (!z.isValid()) {
                alive.remove(b.id);
                removeBar(b.id);
                continue;
            }
            BossBar bb = bars.get(b.id);
            double max = z.getAttribute(Attribute.MAX_HEALTH).getValue();
            List<Player> near = new ArrayList<>(z.getLocation().getNearbyPlayers(40));

            if (bb != null) {
                bb.setProgress(Math.max(0, Math.min(1, z.getHealth() / max)));
                for (Player p : near) bb.addPlayer(p);
                for (Player p : new ArrayList<>(bb.getPlayers())) {
                    if (!near.contains(p)) bb.removePlayer(p);
                }
            }
            if (b.board) {
                for (Player p : near) p.setScoreboard(board(b, z, max));
            }

            int cd = cooldown.merge(b.id, 1, Integer::sum);
            if (cd >= 6) {
                cooldown.put(b.id, 0);
                attack(b, z, near);
            }
        }
    }

    private Scoreboard board(Boss b, LivingEntity z, double max) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("boss", "dummy", Component.text(sc("boss")));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        o.getScore("§7Atak: " + b.attack).setScore(3);
        o.getScore("§cHP: " + (int) z.getHealth() + "/" + (int) max).setScore(2);
        o.getScore("§eRespawn: " + b.respawn + "h").setScore(1);
        return sb;
    }

    // ===================== ATAKI =====================

    private void attack(Boss b, LivingEntity z, List<Player> near) {
        World w = z.getWorld();
        switch (b.attack) {
            case "SLAM" -> {
                w.spawnParticle(Particle.EXPLOSION, z.getLocation(), 1);
                w.playSound(z.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
                for (Player p : near) {
                    if (p.getLocation().distance(z.getLocation()) <= 8) {
                        p.damage(6, z);
                        p.setVelocity(new Vector(0, 0.9, 0));
                    }
                }
            }
            case "LIGHTNING" -> {
                if (!near.isEmpty()) {
                    Player target = near.get(random.nextInt(near.size()));
                    w.strikeLightningEffect(target.getLocation());
                    target.damage(8, z);
                }
            }
            case "SUMMON" -> {
                for (int i = 0; i < 2; i++) {
                    w.spawnEntity(z.getLocation().add(random.nextDouble() * 4 - 2, 0, random.nextDouble() * 4 - 2), EntityType.ZOMBIE);
                }
                w.spawnParticle(Particle.SMOKE, z.getLocation(), 30, 1, 1, 1, 0.05);
            }
            default -> { }
        }
    }

    // ===================== ŚMIERĆ + RESPAWN =====================

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        for (Boss b : bosses.values()) {
            LivingEntity z = alive.get(b.id);
            if (z == null || !z.getUniqueId().equals(e.getEntity().getUniqueId())) continue;
            alive.remove(b.id);
            removeBar(b.id);
            Bukkit.broadcastMessage(pref() + "§cBoss §e" + sc(b.name) + " §czostał pokonany! Respawn za " + b.respawn + "h.");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getScoreboard().getObjective("boss") != null) {
                    p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                }
            }
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (bosses.containsKey(b.id) && b.loc != null) spawnBoss(b, b.loc);
            }, b.respawn * 72000L);
            break;
        }
    }

    // ===================== POMOCNICZE =====================

    private void removeBoss(Boss b) {
        bosses.remove(b.id);
        LivingEntity z = alive.remove(b.id);
        if (z != null) z.remove();
        removeBar(b.id);
        save();
    }

    private void removeBar(String id) {
        BossBar bb = bars.remove(id);
        if (bb != null) bb.removeAll();
    }

    private Location frontLoc(Player p) {
        Vector dir = p.getLocation().getDirection().setY(0).normalize().multiply(3);
        return p.getLocation().add(dir);
    }

    private static String pref() {
        return "§6[WaveBossy] §r";
    }

    static String sc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toLowerCase().toCharArray()) {
            if (ch >= 'a' && ch <= 'z') sb.append(SC[ch - 'a']);
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String next(String[] arr, String cur) {
        int i = Arrays.asList(arr).indexOf(cur);
        return arr[(i + 1) % arr.length];
    }

    private static void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            l.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&7" + s).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    // ===================== ZAPIS / ODCZYT =====================

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Boss b : bosses.values()) {
            String p = "bosses." + b.id + ".";
            y.set(p + "name", b.name);
            y.set(p + "hp", b.hp);
            y.set(p + "armor", b.armor);
            y.set(p + "attack", b.attack);
            y.set(p + "anim", b.anim);
            y.set(p + "bar", b.bar);
            y.set(p + "board", b.board);
            y.set(p + "respawn", b.respawn);
        }
        try {
            y.save(dataFile);
        } catch (Exception ex) {
            getLogger().warning("Nie udało się zapisać bosses.yml: " + ex.getMessage());
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("bosses");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null) continue;
            Boss b = new Boss(id, c.getString("name", id));
            b.hp = c.getDouble("hp", 300);
            b.armor = c.getDouble("armor", 0);
            b.attack = c.getString("attack", "SLAM");
            b.anim = c.getString("anim", "RING");
            b.bar = c.getBoolean("bar", true);
            b.board = c.getBoolean("board", true);
            b.respawn = c.getInt("respawn", 1);
            bosses.put(id, b);
        }
    }
}
