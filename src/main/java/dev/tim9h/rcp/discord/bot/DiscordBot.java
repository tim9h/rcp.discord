package dev.tim9h.rcp.discord.bot;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import dev.tim9h.rcp.discord.DiscordView;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.settings.Settings;
import javafx.application.Platform;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.internal.interactions.CommandDataImpl;

@Singleton
public class DiscordBot {

	private static final String ERROR_WHILE_WAITING_FOR_DISCORD_CLIENT = "Error while waiting for discord client";

	private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		var thread = new Thread(r, "discord-bot");
		thread.setDaemon(true);
		return thread;
	});

	@Inject
	private Settings settings;

	@InjectLogger
	private Logger logger;

	private volatile JDA client;

	private final Map<String, BotCommand> commands = new ConcurrentHashMap<>();

	public CompletableFuture<Boolean> login() {
		return CompletableFuture.supplyAsync(() -> {
			if (client == null) {
				var token = settings.getString(DiscordView.SETTING_TOKEN);
				client = JDABuilder.createLight(token).build();
				logger.info(() -> "Discord bot connected");
				onBotConnected();
				return Boolean.TRUE;
			} else {
				logger.warn(() -> "Discord bot already connected");
				return Boolean.TRUE;
			}
		}, executor).exceptionally(ex -> {
			logger.error(() -> "Error during discord login: " + ex.getMessage(), ex);
			return Boolean.FALSE;
		});
	}

	private void onBotConnected() {
		client.addEventListener(new ListenerAdapter() {
			@Override
			public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
				handleSlashCommand(event);
			}
		});
	}

	private void handleSlashCommand(SlashCommandInteractionEvent event) {
		if (commandAllowed(event.getName(), event.getUser())) {
			var channel = event.getChannel().getName();
			var command = commands.get(event.getName());
			var options = event.getOptions();
			var args = options.stream().map(OptionMapping::getAsString).collect(Collectors.joining(StringUtils.SPACE));
			Platform.runLater(() -> {
				var success = command.action().test(channel, args);
				if (success) {
					event.reply("mkay.").setEphemeral(true).queue();
					logger.info(() -> String.format("Command %s executed successfully by %s", event.getName(),
							event.getUser().getName()));
				} else {
					event.reply("error.").setEphemeral(true).queue();
					logger.error(() -> String.format("Unable to execute command %s by %s", event.getName(),
							event.getUser().getName()));
				}
			});
		} else {
			event.reply("nope.").setEphemeral(true).queue();
			logger.warn(
					() -> String.format("Denying command %s by user %s", event.getName(), event.getUser().getName()));
		}
	}

	private boolean commandAllowed(String command, User user) {
		var com = command.split(StringUtils.SPACE)[0];
		var botCommand = commands.get(com);
		return botCommand != null && (settings.getStringSet(DiscordView.SETTING_DISCORD_USERTAG)
				.contains(user.getName())
				|| !settings.getStringSet(DiscordView.SETTING_MODES).contains("dnd") && !botCommand.adminOnly());
	}

	public CompletableFuture<Void> updateCommands() {
		return CompletableFuture.runAsync(() -> {
			var jda = client;
			if (jda == null) {
				logger.error(() -> "Unable to update commands: client not initialized");
				return;
			}
			// registers slash commands with description. can take up to an hour
			var data = commands.entrySet().stream().filter(entry -> entry.getValue().description() != null)
					.map(entry -> {
						logger.info(() -> String.format("Upserting command %s: %s", entry.getKey(),
								entry.getValue().description()));
						var command = new CommandDataImpl(entry.getKey(), entry.getValue().description());
						if (entry.getValue().options() != null) {
							command.addOptions(entry.getValue().options());
						}
						return command;
					}).toList();
			jda.updateCommands().addCommands(data).queue();
		}, executor);
	}

	public boolean isLoggedIn() {
		return client != null;
	}

	public void addCommand(String commandName, BotCommand command) {
		commands.putIfAbsent(commandName, command);
	}

	public CompletableFuture<Void> sendMessage(String channelName, String message) {
		return CompletableFuture.runAsync(() -> {
			var jda = client;
			if (jda != null) {
				try {
					jda.awaitReady();
					var channels = jda.getTextChannelsByName(channelName, true);
					if (channels.isEmpty()) {
						logger.warn(() -> "Discord channel not found: " + channelName);
						return;
					}
					channels.get(0).sendMessage(message).complete();
				} catch (InterruptedException e) {
					logger.error(() -> ERROR_WHILE_WAITING_FOR_DISCORD_CLIENT, e);
					Thread.currentThread().interrupt();
				}
			} else {
				logger.error(() -> "Unable to send message to discord channel: client not initialized");
			}
		}, executor);
	}

	public CompletableFuture<Void> logout() {
		return CompletableFuture.runAsync(this::disconnect, executor);
	}

	private void disconnect() {
		var jda = client;
		if (jda != null) {
			jda.shutdownNow();
			logger.info(() -> "Discord bot disconnected: " + jda.getStatus());
			client = null;
		} else {
			logger.warn(() -> "Discord bot not connected");
		}
	}

	/**
	 * Sends a farewell message and disconnects, waiting at most a few seconds.
	 */
	public void shutdown(String channelName, String message) {
		if (client == null) {
			return;
		}
		try {
			sendMessage(channelName, message).orTimeout(3, TimeUnit.SECONDS).join();
		} catch (Exception e) {
			logger.warn(() -> "Unable to send farewell message: " + e.getMessage());
		} finally {
			disconnect();
		}
	}

	public void updatePresence(String message) {
		CompletableFuture.runAsync(() -> {
			var jda = client;
			if (jda != null) {
				try {
					jda.awaitReady();
					jda.getPresence().setActivity(message == null ? null : Activity.playing("in " + message));
				} catch (InterruptedException e) {
					logger.error(() -> ERROR_WHILE_WAITING_FOR_DISCORD_CLIENT, e);
					Thread.currentThread().interrupt();
				}
			}
		}, executor).exceptionally(ex -> {
			logger.error(() -> "Unable to update presence: " + ex.getMessage(), ex);
			return null;
		});
	}

	public void disablePresence() {
		updatePresence(null);
	}

}