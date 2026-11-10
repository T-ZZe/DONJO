package fr.donjon;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class DonjonPlugin extends JavaPlugin implements Listener {

    // ===================== CONFIG (modifie ici) =====================
    static final int MAX_KNIGHTS = 36;
    static final double FRAGMENT_CHANCE = 0.17;      // chevaliers -> fragment de clé
    static final double HELL_FRAGMENT_CHANCE = 0.35; // boss -> fragment clé des enfers
    static final double CHESTPLATE_CHANCE = 0.09;    // boss -> plastron
    static final double SWORD_DAMAGE = 12, SLAM_DAMAGE = 10, RAIN_ARROW_DAMAGE = 9;
    // ================================================================

    static final String HALL = "donjon", ARENA = "donjon_boss";
    NamespacedKey K_ITEM, K_MOB, K_ARROW;
    World hall, arena;
    boolean doorClosed = true, arenaLocked = false, bossSpawning = false, phase2 = false;
    WitherSkeleton boss;
    BossBar bar;
    int bossT = 0;
    final Random rnd = new Random();
    final Map<String, Long> cd = new HashMap<>();
    final Map<UUID, Location> lastPos = new HashMap<>();
    final Map<UUID, Integer> still = new HashMap<>();
    final Set<UUID> camo = new HashSet<>();

    // --- modèle 3D du boss ---
    static class Part { Display d; Vector3f off, size; Quaternionf rot; boolean scythe, item; String tag; }
    final List<Part> parts = new ArrayList<>();
    int swing = 0, spinTicks = 0, modelT = 0;
    double spinYaw = 0;
    static final Vector3f PIVOT = new Vector3f(3.4f, 8.2f, 1.9f); // main qui tient la faux

    static class VoidGen extends ChunkGenerator {
        @Override public Location getFixedSpawnLocation(World w, Random r) { return new Location(w, 0.5, 65, -46.5); }
    }

    // ============================ ENABLE ============================
    @Override public void onEnable() {
        K_ITEM = new NamespacedKey(this, "item");
        K_MOB = new NamespacedKey(this, "mob");
        K_ARROW = new NamespacedKey(this, "rain");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("donjon").setExecutor(this);
        registerRecipes();
        getServer().getScheduler().runTask(this, this::init);
    }

    @Override public void onDisable() {
        removeModel();
        if (boss != null) boss.remove();
    }

    void init() {
        hall = load(HALL);
        arena = load(ARENA);
        if (hall.getBlockAt(0, 64, -47).getType().isAir()) buildHall();
        if (arena.getBlockAt(0, 64, 0).getType().isAir()) buildArena();
        hall.setSpawnLocation(0, 65, -47);
        arena.setSpawnLocation(0, 65, -24);
        for (World w : List.of(hall, arena)) { w.setDifficulty(Difficulty.HARD); w.setTime(18000); }
        getServer().getScheduler().runTaskTimer(this, this::knightLoop, 40, 10);
        getServer().getScheduler().runTaskTimer(this, this::topUp, 200, 600);
        getServer().getScheduler().runTaskTimer(this, this::stateLoop, 40, 20);
        getServer().getScheduler().runTaskTimer(this, this::bossTick, 40, 10);
        getServer().getScheduler().runTaskTimer(this, this::modelTick, 40, 2);
        getLogger().info("Donjon prêt.");
    }

    World load(String n) {
        World w = Bukkit.getWorld(n);
        if (w == null) w = new WorldCreator(n).generator(new VoidGen()).createWorld();
        return w;
    }

    boolean dungeon(World w) { return w != null && (w.equals(hall) || w.equals(arena)); }

    // ============================ ITEMS ============================
    ItemStack custom(Material m, String id, String name, NamedTextColor col, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text(name, col).decoration(TextDecoration.ITALIC, false));
        im.lore(Arrays.stream(lore).map(s -> Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        im.setEnchantmentGlintOverride(true);
        im.getPersistentDataContainer().set(K_ITEM, PersistentDataType.STRING, id);
        it.setItemMeta(im);
        return it;
    }
    ItemStack fragment() { return custom(Material.AMETHYST_SHARD, "fragment", "Fragment de Clé", NamedTextColor.GOLD, "9 fragments = 1 Clé du Donjon"); }
    ItemStack cle() { return custom(Material.TRIAL_KEY, "cle_donjon", "Clé du Donjon", NamedTextColor.YELLOW, "Ouvre la grande porte du château"); }
    ItemStack fragmentEnfer() { return custom(Material.NETHERITE_SCRAP, "fragment_enfer", "Fragment de Clé des Enfers", NamedTextColor.RED, "9 fragments = 1 Clé des Enfers"); }
    ItemStack cleEnfer() { return custom(Material.OMINOUS_TRIAL_KEY, "cle_enfer", "Clé des Enfers", NamedTextColor.DARK_RED, "Le Donjon des Enfers arrivera plus tard..."); }

    ItemStack plastron() {
        ItemStack it = custom(Material.NETHERITE_CHESTPLATE, "plastron", "Plastron de la Faucheuse", NamedTextColor.DARK_PURPLE,
                "Force I permanente", "Vous êtes plus grand, portée augmentée", "Immobile 10s : camouflage");
        ItemMeta im = it.getItemMeta();
        EquipmentSlotGroup g = EquipmentSlotGroup.CHEST;
        mod(im, Attribute.ARMOR, "p_armor", 12, AttributeModifier.Operation.ADD_NUMBER, g);
        mod(im, Attribute.ARMOR_TOUGHNESS, "p_tough", 3, AttributeModifier.Operation.ADD_NUMBER, g);
        mod(im, Attribute.KNOCKBACK_RESISTANCE, "p_kb", 0.1, AttributeModifier.Operation.ADD_NUMBER, g);
        mod(im, Attribute.SCALE, "p_scale", 0.3, AttributeModifier.Operation.ADD_SCALAR, g);
        mod(im, Attribute.ENTITY_INTERACTION_RANGE, "p_reach", 1.5, AttributeModifier.Operation.ADD_NUMBER, g);
        it.setItemMeta(im);
        return it;
    }
    void mod(ItemMeta im, Attribute a, String k, double v, AttributeModifier.Operation op, EquipmentSlotGroup g) {
        im.addAttributeModifier(a, new AttributeModifier(new NamespacedKey(this, k), v, op, g));
    }
    boolean isItem(ItemStack s, String id) {
        return s != null && s.hasItemMeta() && id.equals(s.getItemMeta().getPersistentDataContainer().get(K_ITEM, PersistentDataType.STRING));
    }
    void give(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
    }

    void registerRecipes() {
        recipe("cle_donjon", cle(), fragment());
        recipe("cle_enfer", cleEnfer(), fragmentEnfer());
    }
    void recipe(String id, ItemStack result, ItemStack frag) {
        NamespacedKey key = new NamespacedKey(this, id);
        Bukkit.removeRecipe(key);
        ShapelessRecipe r = new ShapelessRecipe(key, result);
        RecipeChoice.ExactChoice c = new RecipeChoice.ExactChoice(frag);
        for (int i = 0; i < 9; i++) r.addIngredient(c);
        Bukkit.addRecipe(r);
    }

    @EventHandler void onPrep(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (r == null) return;
        if (r instanceof Keyed k && k.getKey().getNamespace().equals(getName().toLowerCase(Locale.ROOT))) return;
        for (ItemStack s : e.getInventory().getMatrix())
            if (s != null && s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(K_ITEM)) { e.getInventory().setResult(null); return; }
    }
    @EventHandler void onCraft(CraftItemEvent e) {
        if (isItem(e.getRecipe().getResult(), "cle_enfer"))
            e.getWhoClicked().sendMessage(Component.text("Clé des Enfers forgée ! Le Donjon des Enfers arrivera plus tard...", NamedTextColor.DARK_RED));
    }

    // ============================ COMMANDE ============================
    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Joueurs uniquement."); return true; }
        if (hall == null) { p.sendMessage("Le donjon se charge, réessaie dans un instant."); return true; }
        if (a.length > 0 && a[0].equalsIgnoreCase("quit")) {
            Location back = p.getRespawnLocation();
            if (back == null || dungeon(back.getWorld())) back = Bukkit.getWorlds().get(0).getSpawnLocation();
            p.teleport(back);
            return true;
        }
        if (a.length > 1 && a[0].equalsIgnoreCase("give") && p.hasPermission("donjon.admin")) {
            ItemStack it = switch (a[1].toLowerCase()) {
                case "fragment" -> fragment(); case "cle" -> cle(); case "fragmentenfer" -> fragmentEnfer();
                case "cleenfer" -> cleEnfer(); case "plastron" -> plastron(); default -> null; };
            if (it != null) give(p, it);
            return true;
        }
        topUp();
        p.teleport(new Location(hall, 0.5, 65, -46.5, 0, 0));
        p.showTitle(Title.title(Component.text("LE DONJON", NamedTextColor.DARK_RED), Component.text("Trouve la clé... si tu survis", NamedTextColor.GRAY)));
        return true;
    }

    // ============================ CHEVALIERS ============================
    void topUp() {
        if (hall == null || hall.getPlayers().isEmpty()) return;
        long n = hall.getLivingEntities().stream().filter(e -> e.getPersistentDataContainer().has(K_MOB)).count();
        for (long i = n; i < MAX_KNIGHTS; i++) {
            Location l;
            do { l = new Location(hall, rnd.nextInt(57) - 28 + 0.5, 65, -48 + rnd.nextInt(45) + 0.5); }
            while (Math.hypot(l.getX(), l.getZ() + 47) < 12 || !l.getBlock().getType().isAir());
            spawnKnight(l, rnd.nextDouble() < 0.6);
        }
    }

    static void attr(LivingEntity e, Attribute a, double v) {
        var i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }
    static void noDrops(EntityEquipment eq) {
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND})
            eq.setDropChance(s, 0f);
    }

    void spawnKnight(Location loc, boolean sword) {
        if (sword) {
            hall.spawn(loc, Zombie.class, false, z -> {
                z.customName(Component.text("Chevalier Lourd", NamedTextColor.GRAY)); z.setCustomNameVisible(true);
                z.setBaby(false); z.setShouldBurnInDay(false);
                EntityEquipment eq = z.getEquipment();
                eq.setHelmet(new ItemStack(Material.IRON_HELMET)); eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS)); eq.setBoots(new ItemStack(Material.IRON_BOOTS));
                eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
                noDrops(eq);
                attr(z, Attribute.MAX_HEALTH, 60); z.setHealth(60);
                attr(z, Attribute.ATTACK_DAMAGE, SWORD_DAMAGE);
                attr(z, Attribute.MOVEMENT_SPEED, 0.17);          // lent
                attr(z, Attribute.KNOCKBACK_RESISTANCE, 0.6);
                attr(z, Attribute.FOLLOW_RANGE, 40);
                attr(z, Attribute.SPAWN_REINFORCEMENTS, 0);
                z.setPersistent(true); z.setRemoveWhenFarAway(false);
                z.getPersistentDataContainer().set(K_MOB, PersistentDataType.STRING, "sword");
            });
        } else {
            hall.spawn(loc, Pillager.class, false, p -> {
                p.customName(Component.text("Chevalier Arbalétrier", NamedTextColor.DARK_GRAY)); p.setCustomNameVisible(true);
                p.setCanJoinRaid(false);
                EntityEquipment eq = p.getEquipment();
                eq.setHelmet(new ItemStack(Material.IRON_HELMET)); eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                eq.setItemInMainHand(new ItemStack(Material.CROSSBOW));
                noDrops(eq);
                attr(p, Attribute.MAX_HEALTH, 40); p.setHealth(40);
                attr(p, Attribute.FOLLOW_RANGE, 40);
                p.setPersistent(true); p.setRemoveWhenFarAway(false);
                p.getPersistentDataContainer().set(K_MOB, PersistentDataType.STRING, "bow");
            });
        }
    }

    boolean ready(Entity e, String k, long ms) {
        String key = e.getUniqueId() + k; long now = System.currentTimeMillis();
        if (cd.getOrDefault(key, 0L) > now) return false;
        cd.put(key, now + ms);
        return true;
    }

    void knightLoop() {
        if (hall == null) return;
        for (LivingEntity le : hall.getLivingEntities()) {
            String t = le.getPersistentDataContainer().get(K_MOB, PersistentDataType.STRING);
            if (t == null || !(le instanceof Mob m) || !(m.getTarget() instanceof Player p) || !p.getWorld().equals(hall)) continue;
            double d = le.getLocation().distance(p.getLocation());
            if (t.equals("sword") && d < 5 && ready(le, "slam", 8000)) slam(le);
            else if (t.equals("bow") && d > 4 && d < 30 && ready(le, "rain", 12000)) rain(le, p);
        }
    }

    void ring(Location c, double r, Particle part, int n) {
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            c.getWorld().spawnParticle(part, c.getX() + Math.cos(a) * r, c.getY() + 0.2, c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }
    void knock(Player p, Location from, double h, double y) {
        Vector v = p.getLocation().toVector().subtract(from.toVector()); v.setY(0);
        if (v.lengthSquared() < 1e-4) v = new Vector(1, 0, 0);
        p.setVelocity(v.normalize().multiply(h).setY(y));
    }

    /** Chevalier lourd : coup de sol qui blesse et repousse. */
    void slam(LivingEntity le) {
        hall.playSound(le.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.6f);
        ring(le.getLocation(), 5.5, Particle.CRIT, 40);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!le.isValid()) return;
            Location c = le.getLocation();
            hall.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
            hall.spawnParticle(Particle.EXPLOSION, c, 3, 1.5, 0.2, 1.5, 0);
            ring(c, 5.5, Particle.POOF, 50);
            for (Player p : hall.getPlayers())
                if (p.getLocation().distance(c) <= 5.5) { p.damage(SLAM_DAMAGE, le); knock(p, c, 1.7, 0.8); }
        }, 15);
    }

    /** Arbalétrier : pluie de flèches sur le joueur visé. */
    void rain(LivingEntity le, Player target) {
        hall.playSound(le.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 2f, 0.5f);
        final Location last = target.getLocation().clone();
        new BukkitRunnable() {
            int t = 0; Location c = last;
            @Override public void run() {
                if (!le.isValid()) { cancel(); return; }
                if (target.isOnline() && target.getWorld().equals(hall)) c = target.getLocation();
                ring(c, 3.5, Particle.FLAME, 30);
                if (++t >= 4) {
                    cancel();
                    for (int i = 0; i < 18; i++) {
                        Location l = c.clone().add(rnd.nextDouble() * 6 - 3, 12 + rnd.nextInt(4), rnd.nextDouble() * 6 - 3);
                        Arrow a = hall.spawnArrow(l, new Vector(0, -1, 0), 2.5f, 3f);
                        a.setShooter(le);
                        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                        a.getPersistentDataContainer().set(K_ARROW, PersistentDataType.BYTE, (byte) 1);
                    }
                }
            }
        }.runTaskTimer(this, 0, 5);
    }

    @EventHandler void onArrowHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Arrow a && a.getPersistentDataContainer().has(K_ARROW)) e.setDamage(RAIN_ARROW_DAMAGE);
    }

    @EventHandler void onDeath(EntityDeathEvent e) {
        LivingEntity le = e.getEntity();
        String t = le.getPersistentDataContainer().get(K_MOB, PersistentDataType.STRING);
        if (t == null) return;
        String id = le.getUniqueId().toString();
        cd.keySet().removeIf(k -> k.startsWith(id));
        e.getDrops().clear();
        switch (t) {
            case "sword", "bow" -> {
                e.setDroppedExp(t.equals("bow") ? 1 : 12); // arbalétrier : XP très faible
                if (rnd.nextDouble() < FRAGMENT_CHANCE) e.getDrops().add(fragment());
            }
            case "boss" -> bossDeath(e);
            default -> e.setDroppedExp(0);
        }
    }

    // ============================ PORTE ============================
    boolean inDoor(Block b) { return b.getX() >= -3 && b.getX() <= 3 && b.getY() >= 65 && b.getY() <= 74 && b.getZ() >= 0 && b.getZ() <= 2; }

    @EventHandler void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || hall == null || !b.getWorld().equals(hall) || !inDoor(b)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!doorClosed) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isItem(hand, "cle_donjon")) {
            p.sendMessage(Component.text("La porte est scellée. Il faut la Clé du Donjon.", NamedTextColor.RED));
            hall.playSound(b.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 0.5f);
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        openDoor(p);
    }

    Material doorMat(int x, int y) {
        if (Math.abs(x) == 3 || y == 74) return Material.GILDED_BLACKSTONE;
        if (x == 0) return Material.CHISELED_POLISHED_BLACKSTONE;
        return (y & 1) == 1 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.DEEPSLATE_TILES;
    }
    void buildDoor() {
        for (int x = -3; x <= 3; x++) for (int y = 65; y <= 74; y++) for (int z = 0; z <= 2; z++) set(hall, x, y, z, doorMat(x, y));
        set(hall, 0, 68, 0, Material.CRYING_OBSIDIAN);
        doorClosed = true;
    }

    /** Animation : la porte s'ouvre du centre vers l'extérieur, secousses, flammes, foudre. */
    void openDoor(Player opener) {
        doorClosed = false;
        Location center = new Location(hall, 0.5, 69, 0.5);
        hall.getPlayers().forEach(pl -> pl.showTitle(Title.title(Component.text("LA PORTE S'OUVRE...", NamedTextColor.DARK_RED), Component.text("Le Gardien t'attend", NamedTextColor.GRAY))));
        hall.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.5f);
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (i <= 3) {
                    for (int s : new int[]{-i, i}) for (int y = 65; y <= 74; y++) for (int z = 0; z <= 2; z++) {
                        set(hall, s, y, z, Material.AIR);
                        hall.spawnParticle(Particle.FLAME, s + 0.5, y + 0.5, z + 0.5, 4, 0.3, 0.3, 0.3, 0.05);
                        hall.spawnParticle(Particle.LARGE_SMOKE, s + 0.5, y + 0.5, z + 0.5, 2, 0.3, 0.3, 0.3, 0.02);
                    }
                    hall.playSound(center, Sound.ENTITY_WITHER_BREAK_BLOCK, 3f, 0.6f);
                    hall.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.5f);
                    for (Player pl : hall.getPlayers()) // secousse
                        pl.setVelocity(pl.getVelocity().add(new Vector(rnd.nextGaussian() * 0.06, 0.12, rnd.nextGaussian() * 0.06)));
                } else {
                    hall.strikeLightningEffect(new Location(hall, 0.5, 65, -1.5));
                    hall.spawnParticle(Particle.EXPLOSION_EMITTER, center, 3, 2, 2, 0.5, 0);
                    hall.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 4f, 0.6f);
                    cancel();
                    getServer().getScheduler().runTaskLater(DonjonPlugin.this, DonjonPlugin.this::tryCloseDoor, 600);
                    return;
                }
                i++;
            }
        }.runTaskTimer(this, 10, 6);
    }
    void tryCloseDoor() {
        for (Player p : hall.getPlayers()) {
            Location l = p.getLocation();
            if (l.getX() > -5 && l.getX() < 5 && l.getY() > 64 && l.getY() < 76 && l.getZ() > -2 && l.getZ() < 4) {
                getServer().getScheduler().runTaskLater(this, this::tryCloseDoor, 100);
                return;
            }
        }
        buildDoor();
        hall.playSound(new Location(hall, 0.5, 69, 0.5), Sound.BLOCK_ANVIL_LAND, 3f, 0.4f);
    }

    // ============================ BOUCLE D'ÉTAT (1s) ============================
    void stateLoop() {
        if (hall == null) return;
        if (hall.getTime() < 13000 || hall.getTime() > 23000) hall.setTime(18000);
        if (arena.getTime() < 13000 || arena.getTime() > 23000) arena.setTime(18000);
        // portail
        hall.spawnParticle(Particle.PORTAL, 0.5, 67, 12.5, 30, 1, 1.5, 0.3, 0.5);
        for (Player p : hall.getPlayers()) {
            Location l = p.getLocation();
            if (Math.abs(l.getX()) <= 2.5 && l.getY() >= 65 && l.getY() <= 70 && l.getZ() >= 11 && l.getZ() <= 14) sendToArena(p);
        }
        // arène vide -> reset
        if (arena.getPlayers().isEmpty() && (arenaLocked || boss != null)) {
            if (boss != null) boss.remove();
            arena.getEntitiesByClass(Vex.class).forEach(Entity::remove);
            removeModel();
            boss = null; arenaLocked = false; phase2 = false; bar = null;
        }
        // plastron : force + camouflage
        for (Player p : getServer().getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!isItem(p.getInventory().getChestplate(), "plastron")) {
                if (camo.remove(id)) p.removePotionEffect(PotionEffectType.INVISIBILITY);
                still.remove(id); lastPos.remove(id);
                continue;
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 60, 0, true, false, true));
            Location now = p.getLocation(), prev = lastPos.get(id);
            boolean moved = prev == null || !prev.getWorld().equals(now.getWorld()) || prev.distanceSquared(now) > 0.04;
            lastPos.put(id, now);
            if (moved) {
                still.put(id, 0);
                if (camo.remove(id)) { p.removePotionEffect(PotionEffectType.INVISIBILITY); p.sendActionBar(Component.text("Vous n'êtes plus camouflé", NamedTextColor.GRAY)); }
            } else if (still.merge(id, 1, Integer::sum) >= 10) {
                if (camo.add(id)) p.sendActionBar(Component.text("Vous vous fondez dans le décor...", NamedTextColor.DARK_PURPLE));
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 60, 0, true, false, false));
            }
        }
    }

    @EventHandler void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && camo.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ============================ BOSS : LA FAUCHEUSE ============================
    void sendToArena(Player p) {
        p.teleport(new Location(arena, 0.5, 65, -24.5, 0, 0));
        p.showTitle(Title.title(Component.text("LA FAUCHEUSE", NamedTextColor.DARK_RED), Component.text("Ne reste pas seul...", NamedTextColor.GRAY)));
        if (boss == null && !arenaLocked && !bossSpawning) {
            bossSpawning = true;
            arena.playSound(new Location(arena, 0.5, 65, 0.5), Sound.ENTITY_WITHER_SPAWN, 3f, 0.5f);
            getServer().getScheduler().runTaskLater(this, this::spawnBoss, 100);
        }
    }

    void spawnBoss() {
        bossSpawning = false;
        if (arena.getPlayers().isEmpty()) return;
        arenaLocked = true; phase2 = false; bossT = 0;
        boss = arena.spawn(new Location(arena, 0.5, 65, 10.5), WitherSkeleton.class, false, w -> {
            w.customName(Component.text("La Faucheuse", NamedTextColor.DARK_RED)); w.setCustomNameVisible(true);
            attr(w, Attribute.SCALE, 5.5);
            attr(w, Attribute.MAX_HEALTH, 1024); w.setHealth(1024);
            attr(w, Attribute.ATTACK_DAMAGE, 20);
            attr(w, Attribute.MOVEMENT_SPEED, 0.28);
            attr(w, Attribute.KNOCKBACK_RESISTANCE, 1);
            attr(w, Attribute.ARMOR, 20); attr(w, Attribute.ARMOR_TOUGHNESS, 12);
            attr(w, Attribute.FOLLOW_RANGE, 64);
            w.setInvisible(true); // le vrai visuel = modèle 3D (displays)
            w.setPersistent(true); w.setRemoveWhenFarAway(false);
            w.getPersistentDataContainer().set(K_MOB, PersistentDataType.STRING, "boss");
        });
        buildModel();
        bar = BossBar.bossBar(boss.customName(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
        arena.strikeLightningEffect(boss.getLocation());
        arena.playSound(boss.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 4f, 0.4f);
    }

    void bossTick() {
        if (boss == null) return;
        if (!boss.isValid()) { boss = null; return; }
        List<Player> ps = arena.getPlayers().stream().filter(p -> !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)).toList();
        if (ps.isEmpty()) return;
        bossT++;
        for (Player p : ps) p.showBossBar(bar);
        double hp = boss.getHealth() / boss.getAttribute(Attribute.MAX_HEALTH).getValue();
        bar.progress((float) Math.max(0, Math.min(1, hp)));
        if (!phase2 && hp <= 0.5) enterPhase2(ps);
        Location bl = boss.getLocation();
        Player near = ps.stream().filter(p -> !camo.contains(p.getUniqueId()))
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(bl))).orElse(ps.get(0));
        boss.setTarget(near);
        double d = near.getLocation().distance(bl);
        // PHASE 1
        if (bossT % 8 == 0 && d < 9) slash(9, 14);
        if (bossT % 24 == 10) leap(ps.get(rnd.nextInt(ps.size())));
        if (bossT % 18 == 5) soulPull(ps);
        // PHASE 2
        if (phase2) {
            if (bossT % 16 == 3) skullRain(ps);
            if (bossT % 40 == 20) summon(near);
            if (bossT % 20 == 8) spin();
        }
    }

    void enterPhase2(List<Player> ps) {
        phase2 = true;
        boss.customName(Component.text("La Faucheuse — Forme Spectrale", NamedTextColor.RED));
        attr(boss, Attribute.SCALE, 6.5); attr(boss, Attribute.MOVEMENT_SPEED, 0.36); attr(boss, Attribute.ATTACK_DAMAGE, 28);
        phase2Model();
        bar.name(boss.customName()); bar.color(BossBar.Color.RED);
        arena.strikeLightningEffect(boss.getLocation());
        arena.playSound(boss.getLocation(), Sound.ENTITY_WITHER_SPAWN, 4f, 0.5f);
        for (Player p : ps) p.showTitle(Title.title(Component.text("PHASE 2", NamedTextColor.RED), Component.text("La Faucheuse révèle son vrai visage", NamedTextColor.GRAY)));
    }

    void slash(double r, double dmg) {
        swing = 14;
        Location c = boss.getLocation();
        arena.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 3f, 0.4f);
        ring(c, r * 0.7, Particle.SWEEP_ATTACK, 24); ring(c, r, Particle.LARGE_SMOKE, 40);
        for (Player p : arena.getPlayers())
            if (p.getLocation().distance(c) <= r) { p.damage(dmg, boss); knock(p, c, 1.4, 0.6); }
    }

    void leap(Player target) {
        target.sendActionBar(Component.text("La Faucheuse bondit sur toi !", NamedTextColor.RED));
        final Location t = target.getLocation().clone();
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (boss == null || !boss.isValid()) { cancel(); return; }
                ring(t, 8, Particle.SOUL_FIRE_FLAME, 50);
                if (++i >= 3) {
                    cancel();
                    boss.teleport(t);
                    swing = 14;
                    arena.spawnParticle(Particle.EXPLOSION_EMITTER, t, 2, 3, 0.5, 3, 0);
                    arena.playSound(t, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
                    for (Player p : arena.getPlayers()) if (p.getLocation().distance(t) <= 8) { p.damage(16, boss); knock(p, t, 1.8, 0.9); }
                }
            }
        }.runTaskTimer(this, 0, 10);
    }

    void soulPull(List<Player> ps) {
        Location bl = boss.getLocation();
        arena.playSound(bl, Sound.ENTITY_WITHER_AMBIENT, 4f, 0.5f);
        for (Player p : ps) {
            double d = p.getLocation().distance(bl);
            if (d > 8 && d < 40) {
                p.sendActionBar(Component.text("Les âmes t'attirent...", NamedTextColor.DARK_PURPLE));
                p.setVelocity(bl.toVector().subtract(p.getLocation().toVector()).normalize().multiply(1.2).setY(0.5));
            }
        }
        getServer().getScheduler().runTaskLater(this, () -> { if (boss != null && boss.isValid()) slash(10, 15); }, 22);
    }

    void skullRain(List<Player> ps) {
        for (Player p : ps) for (int i = 0; i < 4; i++) {
            Location s = p.getLocation().add(rnd.nextDouble() * 8 - 4, 12, rnd.nextDouble() * 8 - 4);
            arena.spawn(s, WitherSkull.class, sk -> { sk.setShooter(boss); sk.setDirection(new Vector(0, -1, 0)); sk.setVelocity(new Vector(0, -1.5, 0)); });
        }
    }

    void summon(Player target) {
        if (arena.getEntitiesByClass(Vex.class).size() >= 8) return;
        for (int i = 0; i < 3; i++)
            arena.spawn(boss.getLocation().add(rnd.nextDouble() * 6 - 3, 3, rnd.nextDouble() * 6 - 3), Vex.class, v -> {
                v.customName(Component.text("Âme damnée", NamedTextColor.DARK_PURPLE));
                attr(v, Attribute.MAX_HEALTH, 12); v.setHealth(12);
                v.setTarget(target);
                v.getPersistentDataContainer().set(K_MOB, PersistentDataType.STRING, "minion");
            });
    }

    void spin() {
        spinTicks = 60;
        arena.playSound(boss.getLocation(), Sound.ENTITY_WITHER_SHOOT, 4f, 0.4f);
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (boss == null || !boss.isValid() || i++ >= 6) { cancel(); return; }
                Location c = boss.getLocation();
                ring(c, 9, Particle.SWEEP_ATTACK, 36);
                arena.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 3f, 0.5f);
                for (Player p : arena.getPlayers()) if (p.getLocation().distance(c) <= 9) {
                    p.damage(6, boss); p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1));
                }
            }
        }.runTaskTimer(this, 0, 10);
    }

    @EventHandler void onBossHurt(EntityDamageEvent e) {
        if (boss == null || !e.getEntity().equals(boss)) return;
        int n = arena.getPlayers().size();
        double m = n >= 4 ? 0.8 : n == 3 ? 0.6 : n == 2 ? 0.4 : 0.2; // seul = impossible
        e.setDamage(e.getDamage() * m);
    }

    void bossDeath(EntityDeathEvent e) {
        e.setDroppedExp(1000);
        arena.spawnParticle(Particle.EXPLOSION_EMITTER, boss.getLocation().add(0, 6, 0), 6, 3, 4, 3, 0);
        arena.playSound(boss.getLocation(), Sound.ENTITY_WITHER_DEATH, 4f, 0.6f);
        removeModel();
        arena.getEntitiesByClass(Vex.class).forEach(Entity::remove);
        for (Player p : arena.getPlayers()) {
            p.showTitle(Title.title(Component.text("LA FAUCHEUSE EST VAINCUE", NamedTextColor.GOLD), Component.text("/donjon quit pour sortir", NamedTextColor.GRAY)));
            if (rnd.nextDouble() < HELL_FRAGMENT_CHANCE) {
                give(p, fragmentEnfer());
                p.sendMessage(Component.text("Tu obtiens un Fragment de Clé des Enfers !", NamedTextColor.RED));
            }
            if (rnd.nextDouble() < CHESTPLATE_CHANCE) {
                give(p, plastron());
                p.sendMessage(Component.text("Tu obtiens le Plastron de la Faucheuse !!!", NamedTextColor.DARK_PURPLE));
            }
        }
        if (bar != null) { final BossBar b = bar; getServer().getOnlinePlayers().forEach(pl -> pl.hideBossBar(b)); }
        boss = null; phase2 = false; // arenaLocked reste true jusqu'à ce que l'arène soit vide
    }

    @EventHandler void onWorld(PlayerChangedWorldEvent e) { if (bar != null) e.getPlayer().hideBossBar(bar); }

    // ============================ MODÈLE 3D (displays) ============================
    void setupDisplay(Display d, boolean glow) {
        d.setPersistent(false);
        d.setTeleportDuration(2);
        d.setInterpolationDuration(2);
        d.setViewRange(3f);
        if (glow) d.setBrightness(new Display.Brightness(15, 15));
    }
    void addBlock(Material m, String tag, boolean scythe, float cx, float cy, float cz, float sx, float sy, float sz, float rxDeg) {
        Part p = new Part();
        p.tag = tag; p.scythe = scythe;
        p.off = new Vector3f(cx, cy, cz); p.size = new Vector3f(sx, sy, sz);
        p.rot = new Quaternionf().rotateX((float) Math.toRadians(rxDeg));
        p.d = arena.spawn(boss.getLocation(), BlockDisplay.class, d -> { d.setBlock(m.createBlockData()); setupDisplay(d, tag.equals("eye")); });
        parts.add(p);
    }
    void addItem(Material m, String tag, float cx, float cy, float cz, float scale) {
        Part p = new Part();
        p.tag = tag; p.item = true;
        p.off = new Vector3f(cx, cy, cz); p.size = new Vector3f(scale, scale, scale); p.rot = new Quaternionf();
        p.d = arena.spawn(boss.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(m));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            setupDisplay(d, false);
        });
        parts.add(p);
    }

    /** La Faucheuse : robe noire, capuche, crâne, yeux lumineux, grande faux. (axe +Z = devant, taille ~13 blocs) */
    void buildModel() {
        removeModel();
        Material B = Material.BLACK_CONCRETE;
        addBlock(B, "robe", false, 0, 1.5f, 0, 5f, 3f, 4f, 0);
        addBlock(B, "robe", false, 0, 5f, 0, 4f, 4f, 3.2f, 0);
        addBlock(Material.CRYING_OBSIDIAN, "belt", false, 0, 6.9f, 0, 4.2f, 0.5f, 3.4f, 0);
        addBlock(B, "robe", false, 0, 8.5f, 0, 3.4f, 3f, 2.6f, 0);
        addBlock(Material.POLISHED_BLACKSTONE_BRICKS, "robe", false, 0, 10.2f, 0, 6f, 1f, 2.8f, 0);
        addBlock(B, "robe", false, 0, 11.8f, 0, 3.2f, 2.2f, 3.2f, 0);
        addBlock(B, "robe", false, 0, 11.2f, -1.7f, 2.6f, 3f, 0.8f, 0);
        addItem(Material.WITHER_SKELETON_SKULL, "skull", 0, 11.7f, 0.9f, 2.6f);
        addBlock(Material.SEA_LANTERN, "eye", false, -0.5f, 11.9f, 1.75f, 0.45f, 0.3f, 0.1f, 0);
        addBlock(Material.SEA_LANTERN, "eye", false, 0.5f, 11.9f, 1.75f, 0.45f, 0.3f, 0.1f, 0);
        addBlock(B, "robe", false, -3.5f, 8.9f, 0.4f, 1.1f, 3.4f, 1.1f, -25f);
        addBlock(B, "robe", true, 3.4f, 9.0f, 0.9f, 1.1f, 3.2f, 1.1f, -50f);
        addBlock(Material.DARK_OAK_WOOD, "scythe", true, 3.4f, 8.2f, 1.9f, 0.3f, 15f, 0.3f, 0);
        addBlock(Material.NETHERITE_BLOCK, "scythe", true, 3.4f, 15.9f, 1.9f, 0.6f, 0.6f, 0.6f, 0);
        addBlock(Material.IRON_BLOCK, "blade", true, 3.4f, 15.4f, 2.8f, 0.4f, 0.6f, 2.0f, 0);
        addBlock(Material.IRON_BLOCK, "blade", true, 3.4f, 14.9f, 4.2f, 0.35f, 0.5f, 1.8f, 30f);
        addBlock(Material.IRON_BLOCK, "blade", true, 3.4f, 14.1f, 5.2f, 0.3f, 0.45f, 1.5f, 55f);
        swing = 0; spinTicks = 0; spinYaw = 0; modelT = 0;
    }

    void phase2Model() {
        for (Part p : parts) {
            p.d.setGlowing(true);
            p.d.setGlowColorOverride(Color.RED);
            switch (p.tag) {
                case "eye" -> ((BlockDisplay) p.d).setBlock(Material.REDSTONE_BLOCK.createBlockData());
                case "blade" -> ((BlockDisplay) p.d).setBlock(Material.MAGMA_BLOCK.createBlockData());
                case "belt" -> ((BlockDisplay) p.d).setBlock(Material.SHROOMLIGHT.createBlockData());
                case "skull" -> { ((ItemDisplay) p.d).setItemStack(new ItemStack(Material.DRAGON_HEAD)); p.size.mul(1.15f); }
                default -> { }
            }
        }
    }

    void removeModel() {
        for (Part p : parts) if (p.d != null && p.d.isValid()) p.d.remove();
        parts.clear();
    }

    /** Met à jour position / rotation / animation des pièces (toutes les 2 ticks). */
    void modelTick() {
        if (parts.isEmpty()) return;
        if (boss == null || !boss.isValid()) { removeModel(); return; }
        modelT++;
        Location b = boss.getLocation();
        float s = (float) (boss.getAttribute(Attribute.SCALE).getValue() / 5.5);
        double bob = (phase2 ? 1.2 * s : 0) + Math.sin(modelT * 0.15) * (phase2 ? 0.4 : 0.12) * s;
        float ang = 8f;
        if (spinTicks > 0) { spinTicks -= 2; spinYaw = (spinYaw + 50) % 360; ang = 85f; }
        else {
            spinYaw = 0;
            if (swing > 0) { swing -= 2; double pr = 1 - swing / 14.0; ang = (float) (8 + 110 * Math.sin(pr * Math.PI)); }
        }
        Quaternionf yq = new Quaternionf().rotateY((float) Math.toRadians(-(b.getYaw() + spinYaw)));
        Quaternionf rg = new Quaternionf().rotateX((float) Math.toRadians(ang));
        for (Part p : parts) {
            if (p.d == null || !p.d.isValid()) continue;
            Vector3f off = new Vector3f(p.off);
            Quaternionf rot = new Quaternionf(p.rot);
            if (p.scythe) { off.sub(PIVOT).rotate(rg).add(PIVOT); rot = new Quaternionf(rg).mul(p.rot); }
            off.mul(s);
            Quaternionf L = new Quaternionf(yq).mul(rot);
            Vector3f size = new Vector3f(p.size).mul(s);
            Vector3f tr = p.item ? new Vector3f() : new Vector3f(size).mul(0.5f).rotate(L).negate();
            Vector3f w = new Vector3f(off).rotate(yq);
            p.d.setTransformation(new Transformation(tr, L, size, new Quaternionf()));
            p.d.setInterpolationDelay(0);
            p.d.teleport(new Location(arena, b.getX() + w.x, b.getY() + bob + w.y, b.getZ() + w.z));
        }
    }

    // ============================ PROTECTIONS ============================
    @EventHandler void onBreak(BlockBreakEvent e) { if (dungeon(e.getBlock().getWorld()) && e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true); }
    @EventHandler void onPlace(BlockPlaceEvent e) { if (dungeon(e.getBlock().getWorld()) && e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true); }
    @EventHandler void onExplode(EntityExplodeEvent e) { if (dungeon(e.getEntity().getWorld())) e.blockList().clear(); }
    @EventHandler void onPortal(PlayerPortalEvent e) { if (dungeon(e.getFrom().getWorld())) e.setCancelled(true); }
    @EventHandler void onSpawn(CreatureSpawnEvent e) {
        if (dungeon(e.getLocation().getWorld()) && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG)
            e.setCancelled(true);
    }

    // ============================ CONSTRUCTION ============================
    static void set(World w, int x, int y, int z, Material m) { w.getBlockAt(x, y, z).setType(m, false); }
    static void fill(World w, int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(w, x, y, z, m);
    }

    void buildHall() {
        getLogger().info("Construction du château (ça peut prendre quelques secondes)...");
        fill(hall, -32, 63, -52, 32, 89, 16, Material.STONE_BRICKS);          // bloc du château
        fill(hall, -30, 65, -50, 30, 85, -1, Material.AIR);                    // grande salle 61x50
        fill(hall, -8, 65, 3, 8, 80, 14, Material.AIR);                        // sanctuaire du portail
        for (int x = -30; x <= 30; x++) for (int z = -50; z <= -1; z++)
            set(hall, x, 64, z, ((x + z) & 1) == 0 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_TILES);
        for (int x = -8; x <= 8; x++) for (int z = 3; z <= 14; z++) set(hall, x, 64, z, Material.BLACKSTONE);
        for (int z = -50; z <= -1; z++) for (int x = -1; x <= 1; x++) set(hall, x, 65, z, Material.RED_CARPET);
        for (int x : new int[]{-20, -10, 10, 20}) for (int z : new int[]{-44, -34, -24, -14, -6}) { // piliers
            fill(hall, x, 65, z, x, 85, z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(hall, x, 65, z, Material.GILDED_BLACKSTONE);
            set(hall, x + 1, 65, z, Material.SOUL_CAMPFIRE);
        }
        for (int x = -24; x <= 24; x += 12) for (int z = -42; z <= -6; z += 12) set(hall, x, 85, z, Material.SHROOMLIGHT);
        for (int z = -45; z <= -5; z += 8) { set(hall, -30, 72, z, Material.SHROOMLIGHT); set(hall, 30, 72, z, Material.SHROOMLIGHT); }
        buildDoor();
        // portail (cadre obsidienne + portail axe X) derrière la porte
        fill(hall, -2, 65, 12, 2, 69, 12, Material.OBSIDIAN);
        for (int x = -1; x <= 1; x++) for (int y = 66; y <= 68; y++) {
            Block b = hall.getBlockAt(x, y, 12);
            b.setType(Material.NETHER_PORTAL, false);
            if (b.getBlockData() instanceof Orientable o) { o.setAxis(Axis.X); b.setBlockData(o, false); }
        }
        getLogger().info("Château construit.");
    }

    void buildArena() {
        getLogger().info("Construction de l'arène...");
        for (int x = -36; x <= 36; x++) for (int z = -36; z <= 36; z++) {
            double d = Math.hypot(x, z);
            if (d <= 32) set(arena, x, 64, z, (d >= 11.5 && d <= 13) ? Material.CRYING_OBSIDIAN : (d >= 28 && d < 29) ? Material.GILDED_BLACKSTONE : Material.BLACKSTONE);
            else if (d <= 34.5) fill(arena, x, 64, z, x, 95, z, Material.POLISHED_BLACKSTONE_BRICKS);
            if (d <= 34.5) set(arena, x, 96, z, Material.BARRIER);
        }
        for (int k = 0; k < 8; k++) {
            int x = (int) Math.round(22 * Math.cos(k * Math.PI / 4)), z = (int) Math.round(22 * Math.sin(k * Math.PI / 4));
            fill(arena, x, 65, z, x, 80, z, Material.DEEPSLATE_BRICKS);
            set(arena, x, 81, z, Material.SOUL_CAMPFIRE);
        }
        getLogger().info("Arène construite.");
    }
}
