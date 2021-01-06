package at.haha.signedit;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SignCommand implements CommandExecutor, Listener {

	private final Pattern hexColorPattern = Pattern.compile("&[#xX][0-9a-fA-F]{6}");
	private final Pattern removePattern = Pattern.compile("(&[0-9a-fk-rA-FK-R])|(&[#xX][0-9a-fA-F]{6})");

	private final Set<Player> enabledPlayers = new HashSet<>();
	private final Hashtable<String, String> messages = new Hashtable<>();
	private final Hashtable<Player, String[]> signs = new Hashtable<>();


	//sign -> toggles enabled
	//sign <line> <text> -> edit line in text

	//left click -> copy
	//if enabled placed -> written automatic

	public SignCommand(JavaPlugin plugin) {
		plugin.getServer().getPluginManager().registerEvents(this, plugin);

		plugin.saveResource("messages.yml", false);

		File file = new File(plugin.getDataFolder(), "messages.yml");
		YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
		for (String key : cfg.getKeys(false)) {
			messages.put(key, cfg.getString(key));
		}
	}


	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
		if (!(sender instanceof Player)) {
			sender.sendMessage(ChatColor.RED + "This command can only be executed by players!");
			return true;
		}

		Player player = (Player) sender;


		if (args.length == 0) {
			// ENABLE / DISABLE
			if (enabledPlayers.contains(player)) {
				enabledPlayers.remove(player);
				onDisable(player);
				player.sendMessage(messages.get("sign_disable"));
			} else {
				enabledPlayers.add(player);
				try {
					onEnable(player);
				} catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException | NoSuchFieldException e) {
					e.printStackTrace();
				}
				player.sendMessage(messages.get("sign_enable"));
			}
			return true;
		}


		int line;
		try {
			line = Integer.parseInt(args[0]);
		} catch (NumberFormatException e) {
			player.sendMessage(messages.get("line_not_number"));
			return true;
		}
		if (line < 1 || line > 4) {
			player.sendMessage(messages.get("line_out_of_bounds"));
			return true;
		}

		boolean colorPermission = player.hasPermission("signedit.sign.color");

		String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

		String removedColorCodes = colorPermission ? removePattern.matcher(text).replaceAll("") : text;
		if (removedColorCodes.length() > 15) {
			player.sendMessage(messages.get("text_too_long"));
			return true;
		}

		String[] sign = signs.get(player);
		if (sign == null) {
			sign = new String[4];
			Arrays.fill(sign, "");
		}

		sign[line - 1] = colorPermission ? translateChatColors(text) : text;


		signs.put(player, sign);

		player.sendMessage(messages.get("sign_edited"));
		return true;
	}


	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	void onBlockPlace(BlockPlaceEvent event) {
		if (!Tag.SIGNS.isTagged(event.getBlock().getType())) return;

		final Player player = event.getPlayer();

		if (!enabledPlayers.contains(player)) return;
		if (!player.hasPermission("signedit.sign.command")) return;

		Sign sign = ((Sign) event.getBlock().getState());

		String[] lines = signs.get(player);
		if (lines == null) lines = new String[]{"", "", "", ""};


		sign.setEditable(false);
		SignChangeEvent signEvent = new SignChangeEvent(event.getBlock(), player, lines);
		Bukkit.getServer().getPluginManager().callEvent(signEvent);
		if (signEvent.isCancelled()) return;
		for (int i = 0; i < lines.length; i++) {
			sign.setLine(i, lines[i]);
		}
		sign.update();
	}

	@EventHandler
	void onPlayerInteract(PlayerInteractEvent event) {
		if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
		if (!enabledPlayers.contains(event.getPlayer())) return;
		if (!(Objects.requireNonNull(event.getClickedBlock()).getState() instanceof Sign)) return;
		signs.put(event.getPlayer(), ((Sign) Objects.requireNonNull(event.getClickedBlock()).getState()).getLines());
	}

	@EventHandler
	void onPlayerQuit(PlayerQuitEvent event) {
		enabledPlayers.remove(event.getPlayer());
		signs.remove(event.getPlayer());
	}


	private String translateChatColors(String text) {
		Matcher matcher = hexColorPattern.matcher(text);
		while (matcher.find()) {
			String color = text.substring(matcher.start(), matcher.end());
			text = text.replace(color, ChatColor.of("#" + color.substring(2)).toString());
			matcher = hexColorPattern.matcher(text);
		}
		return ChatColor.translateAlternateColorCodes('&', text);
	}

	private void onEnable(Player player) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, NoSuchFieldException {
		Object nmsPlayer = player.getClass().getDeclaredMethod("getHandle").invoke(player);
		Object playerConnection = nmsPlayer.getClass().getDeclaredField("playerConnection").get(nmsPlayer);
		Object networkManager = playerConnection.getClass().getDeclaredField("networkManager").get(playerConnection);
		Channel channel = (Channel) networkManager.getClass().getDeclaredField("channel").get(networkManager);
		Objects.requireNonNull(channel).pipeline().addBefore("packet_handler", "sign_packet_handler", new SignPacketRemover());
	}

	private void onDisable(Player player) {
		((CraftPlayer) player).getHandle().playerConnection.networkManager.channel.pipeline().remove("sign_packet_handler");
	}


	private static class SignPacketRemover extends ChannelDuplexHandler {
		private static final String nmsPackage = "net.minecraft.server." + Bukkit.getServer().getClass().getPackage().getName().replace(".", ",").split(",")[3];
		private static final Class<?> packetPlayOutSignGui;

		static {
			Class<?> tempPacketClass;
			try {
				tempPacketClass = Class.forName(nmsPackage + ".PacketPlayOutOpenSignEditor");
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
				tempPacketClass = null;
			}
			packetPlayOutSignGui = tempPacketClass;
		}

		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
			if (msg.getClass() == packetPlayOutSignGui) return;
			super.write(ctx, msg, promise);
		}
	}

}
