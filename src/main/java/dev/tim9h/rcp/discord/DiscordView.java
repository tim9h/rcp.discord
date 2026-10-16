package dev.tim9h.rcp.discord;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;

import dev.tim9h.rcp.discord.bot.BotCommand;
import dev.tim9h.rcp.discord.bot.DiscordBot;
import dev.tim9h.rcp.event.Event;
import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.settings.Settings;
import dev.tim9h.rcp.spi.CommandBuilder;
import dev.tim9h.rcp.spi.CommandNode;
import dev.tim9h.rcp.spi.Mode;
import dev.tim9h.rcp.spi.Plugin;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

public class DiscordView implements Plugin {

	public static final String SETTING_TOKEN = "discord.bot.token";

	private static final String SETTING_CHANNEL = "discord.bot.notificationchannel";

	public static final String SETTING_DISCORD_USERTAG = "discord.privileged.user.tag";

	public static final String SETTING_MODES = "core.modes";

	@InjectLogger
	private Logger logger;

	@Inject
	private DiscordBot bot;

	@Inject
	private EventManager eventManager;

	private String channelName;

	@Inject
	private Settings settings;

	@Override
	public String getName() {
		return "Discord Bot";
	}

	@Override
	public String getId() {
		return "discord";
	}

	@Override
	public void init() {
		logger.info(() -> "Created DiscordView");
		channelName = settings.getString(SETTING_CHANNEL);
	}

	@Override
	public void initBus(EventManager em) {
		Plugin.super.initBus(eventManager);
		em.listen("LOGILED_LIGHTING_CHANGED", color -> bot.updatePresence(StringUtils.join(color)));
	}

	@Override
	public void onSettingsChanged() {
		channelName = settings.getString(SETTING_CHANNEL);
	}

	private void connectDiscordBot() {
		if (!bot.isLoggedIn()) {
			eventManager.showWaitingIndicator();
			registerBotCommands();
			bot.login().thenAccept(loginSuccess -> {
				if (loginSuccess.booleanValue()) {
					onBotConnected();
				} else {
					eventManager.echo("Unable to start Discord Bot");
				}
			});
		} else {
			eventManager.echo(StringUtils.EMPTY, "Discord bot already started");
		}
	}

	private void onBotConnected() {
		eventManager.echo("Discord bot started");
		bot.sendMessage(channelName, "Hello there");

		var modes = settings.getStringSet(SETTING_MODES);
		if (modes.contains("logiled") && !modes.contains("alert")) {
			bot.updatePresence(settings.getString("logiled.lighting.color"));
		}
	}

	private void registerBotCommands() {
		bot.addCommand("logiled", new BotCommand((_, args) -> {
			logger.info(() -> "Discord: Logiled");
			if (!settings.getStringSet(SETTING_MODES).contains("alert")) {
				eventManager.post(new Event("LOGILED", args));
				return true;
			} else {
				return false;
			}
		}, "Sets the lighting of Logitech periphery",
				Arrays.asList(new OptionData(OptionType.STRING, "color", "name/hex/rgb", true))));

		bot.addCommand("next", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Next song");
			eventManager.post(new Event("next"));
			return true;
		}, "Plays next song"));

		bot.addCommand("previous", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Previous song");
			eventManager.post(new Event("previous"));
			return true;
		}, "Plays previous song"));

		bot.addCommand("play", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Play/pause");
			eventManager.post(new Event("play"));
			return true;
		}, "Play/pause music"));

		bot.addCommand("pause", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Play/pause");
			eventManager.post(new Event("pause"));
			return true;
		}, "Play/pause music"));

		bot.addCommand("stop", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Stop");
			eventManager.post(new Event("stop"));
			return true;
		}, "Stops playing music"));

		bot.addCommand("lock", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Lock");
			eventManager.post(new Event("lock"));
			return true;
		}, "Lock workstation of host computer", true));

		bot.addCommand("shutdown", new BotCommand((_, args) -> {
			logger.info(() -> "Discord: Shutdown");
			eventManager.post(new Event("shutdown", args));
			return true;
		}, "Shutdown host computer",
				Arrays.asList(new OptionData(OptionType.STRING, "when", "Minutes/Hours/Time", false)), true));
	}

	@Override
	public void onShutdown() {
		if (bot.isLoggedIn()) {
			bot.sendMessage(channelName, "It's over").thenRun(bot::logout);
		}
	}

	@Override
	public Optional<List<Mode>> getModes() {
		return Optional.of(Arrays.asList(new Mode() {

			@Override
			public String getName() {
				return "discord";
			}

			@Override
			public void onEnable() {
				connectDiscordBot();
			}

			@Override
			public Optional<CommandNode> getModeCommands() {
				return new CommandBuilder().command("discord").child("update", _ -> {
					CompletableFuture.runAsync(bot::updateCommands);
					eventManager.echo("Updating discord slash commands");
				}).up().child("token", _ -> {
					var token = settings.getString(SETTING_TOKEN);
					eventManager.echo("Current bot token", token);
				}).argumentAction(token -> {
					settings.persist(SETTING_TOKEN, token);
					eventManager.echo("Discord Token updated");
				}).up().child("channel", _ -> {
					var channel = settings.getString(SETTING_CHANNEL);
					eventManager.echo("Current bot notification channel", channel);
				}).argumentAction(channel -> {
					settings.persist(SETTING_CHANNEL, channel);
					channelName = channel;
					eventManager.echo("Bot notification channel updated");
				}).build();
			}

			@Override
			public void onDisable() {
				bot.sendMessage(channelName, "It's over").thenRun(bot::logout);
				eventManager.echo("Discord bot disconnected");
			}
		}));
	}

	@Override
	public Map<String, String> getSettingsContributions() {
		Map<String, String> contributions = new HashMap<>();
		contributions.put(SETTING_CHANNEL, "general");
		contributions.put(SETTING_TOKEN, StringUtils.EMPTY);
		contributions.put(SETTING_DISCORD_USERTAG, StringUtils.EMPTY);
		return contributions;
	}

}
