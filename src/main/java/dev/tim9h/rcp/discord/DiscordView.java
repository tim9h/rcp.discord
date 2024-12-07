package dev.tim9h.rcp.discord;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;

import dev.tim9h.rcp.discord.bot.BotCommand;
import dev.tim9h.rcp.discord.bot.DiscordBot;
import dev.tim9h.rcp.event.CcEvent;
import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.settings.Settings;
import dev.tim9h.rcp.spi.CCard;
import dev.tim9h.rcp.spi.Mode;
import dev.tim9h.rcp.spi.TreeNode;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

public class DiscordView implements CCard {

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
	public void init() {
		logger.info(() -> "Created DiscordView");
		channelName = settings.getString(DiscordViewFactory.SETTING_CHANNEL);
	}

	@Override
	public void initBus(EventManager em) {
		CCard.super.initBus(eventManager);
		em.listen("LOGILED_LIGHTING_CHANGED", color -> bot.updatePresence(StringUtils.join(color)));
	}

	@Override
	public void onSettingsChanged() {
		channelName = settings.getString(DiscordViewFactory.SETTING_CHANNEL);
	}

	private void connectDiscordBot() {
		if (!bot.isLoggedIn()) {
			eventManager.showWaitingIndicator();
			registerBotCommands();
			bot.login().thenAccept(loginSuccess -> {
				if (loginSuccess.booleanValue()) {
					onBotConnected();
				} else {
					eventManager.echoAsync("Unable to start Discord Bot");
				}
			});
		} else {
			eventManager.echo(StringUtils.EMPTY, "Discord bot already started");
		}
	}

	private void onBotConnected() {
		eventManager.echoAsync("Discord bot started");
		bot.sendMessage(channelName, "Hello there");

		var modes = settings.getStringSet(DiscordViewFactory.SETTING_MODES);
		if (modes.contains("logiled") && !modes.contains("alert")) {
			bot.updatePresence(settings.getString("logiled.lighting.color"));
		}
	}

	private void registerBotCommands() {
		bot.addCommand("logiled", new BotCommand((_, args) -> {
			logger.info(() -> "Discord: Logiled");
			if (!settings.getStringSet(DiscordViewFactory.SETTING_MODES).contains("alert")) {
				eventManager.post(new CcEvent("LOGILED", args));
				return true;
			} else {
				return false;
			}
		}, "Sets the lighting of Logitech periphery",
				Arrays.asList(new OptionData(OptionType.STRING, "color", "name/hex/rgb", true))));

		bot.addCommand("next", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Next song");
			eventManager.post(new CcEvent("next"));
			return true;
		}, "Plays next song"));

		bot.addCommand("previous", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Previous song");
			eventManager.post(new CcEvent("previous"));
			return true;
		}, "Plays previous song"));

		bot.addCommand("play", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Play/pause");
			eventManager.post(new CcEvent("play"));
			return true;
		}, "Play/pause music"));

		bot.addCommand("pause", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Play/pause");
			eventManager.post(new CcEvent("pause"));
			return true;
		}, "Play/pause music"));

		bot.addCommand("stop", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Stop");
			eventManager.post(new CcEvent("stop"));
			return true;
		}, "Stops playing music"));

		bot.addCommand("lock", new BotCommand((_, _) -> {
			logger.info(() -> "Discord: Lock");
			eventManager.post(new CcEvent("lock"));
			return true;
		}, "Lock workstation of host computer", true));

		bot.addCommand("shutdown", new BotCommand((_, args) -> {
			logger.info(() -> "Discord: Shutdown");
			eventManager.post(new CcEvent("shutdown", args));
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
			public void onCommand(String command, String... args) {
				var join = StringUtils.join(args, StringUtils.SPACE);
				if ("update".equals(command)) {
					CompletableFuture.runAsync(bot::updateCommands);
					eventManager.echo("Updating discord slash commands");
				} else if ("token".equals(command)) {
					settings.persist(DiscordViewFactory.SETTING_TOKEN, join);
					eventManager.echo("Discord Token updated");
				} else if ("channel".equals(command)) {
					settings.persist(DiscordViewFactory.SETTING_CHANNEL, join);
					channelName = join;
					eventManager.echo("Bot notification channel updated");
				}
			}

			@Override
			public void onDisable() {
				bot.sendMessage(channelName, "It's over").thenRun(bot::logout);
				eventManager.echo("Discord bot disconnected");
			}

			@Override
			public TreeNode<String> getCommandTree() {
				var commands = Mode.super.getCommandTree();
				commands.add("update", "token", "channel");
				return commands;
			}
		}));
	}

}
