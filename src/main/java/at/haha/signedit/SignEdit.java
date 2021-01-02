package at.haha.signedit;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class SignEdit extends JavaPlugin {

	@Override
	public void onEnable() {
		Objects.requireNonNull(getCommand("sign")).setExecutor(new SignCommand(this));
	}
}
