package dev.tim9h.rcp.discord.bot;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import dev.tim9h.rcp.discord.DiscordViewFactory;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.settings.Settings;
import javafx.application.Platform;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.internal.interactions.CommandDataImpl;

@Singleton
public class DiscordBot {

	private static final String ERROR_WHILE_WAITING_FOR_DISCORD_CLIENT = "Error while waiting for discord client";

	@Inject
	private Settings settings;

	@InjectLogger
	private Logger logger;

	private JDA client;

	private Map<String, BotCommand> commands;

	public CompletableFuture<Boolean> login() {
		return CompletableFuture.supplyAsync(() -> {
			if (client == null) {
				var token = settings.getString(DiscordViewFactory.SETTING_TOKEN);
				client = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
						.build();
				logger.info(() -> "Discord bot connected");
				onBotConnected();
				return Boolean.TRUE;
			} else {
				logger.warn(() -> "Discord bot already connected");
				return Boolean.TRUE;
			}
		}).exceptionally(ex -> {
			logger.error(() -> "Error during discord login: " + ex.getMessage(), ex);
			return Boolean.FALSE;
		});
	}

	private void onBotConnected() {
		client.addEventListener(new ListenerAdapter() {
			@Override
			public void onMessageReceived(MessageReceivedEvent event) {
				handleMessageReceived(event);
			}

			@Override
			public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
				handleSlashCommand(event);
			}
		});
	}

	private void handleMessageReceived(MessageReceivedEvent event) {
		String content = event.getMessage().getContentRaw();
		if (content.startsWith("!") && commandAllowed(content.substring(1), event.getAuthor())) {
			var channel = event.getChannel().getName();
			var contentStripped = content.substring(1).trim();
			var split = contentStripped.split(" ");
			var commandName = split[0];
			var args = split.length > 1 ? contentStripped.replace(commandName, StringUtils.EMPTY).trim() : null;

			if (commands.containsKey(commandName)) {
				BotCommand command = commands.get(commandName);
				Platform.runLater(() -> command.action().test(channel, args));
			}
		} else {
			logger.warn(() -> "Ignoring command " + event.getMessage().getContentRaw() + " by user "
					+ event.getAuthor().getName());
		}
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
		return (commands.get(com) != null) &&
				(settings.getStringSet(DiscordViewFactory.SETTING_DISCORD_USERTAG).contains(user.getName()) ||
				!settings.getStringSet(DiscordViewFactory.SETTING_MODES).contains("dnd") && !commands.get(command).adminOnly());
	}

	public void updateCommands() {
		logger.info(() -> "Deleting all commands");
		client.retrieveCommands().complete()
				.forEach(oldCommand -> client.deleteCommandById(oldCommand.getId()).queue());

		// registers slash commands with description. can take up to an hour
		commands.entrySet().stream().filter(entry -> entry.getValue().description() != null).forEach(entry -> {
			logger.info(
					() -> String.format("Upserting command %s: %s", entry.getKey(), entry.getValue().description()));
			var command = new CommandDataImpl(entry.getKey(), entry.getValue().description());
			if (entry.getValue().options() != null) {
				command.addOptions(entry.getValue().options());
			}
			client.upsertCommand(command).queue();
		});
	}

	public boolean isLoggedIn() {
		return client != null;
	}

	public void addCommand(String commandName, BotCommand command) {
		if (commands == null) {
			commands = new HashMap<>();
		}
		commands.putIfAbsent(commandName, command);
	}

	public CompletableFuture<Void> sendMessage(String channelName, String message) {
		return CompletableFuture.runAsync(() -> {
			if (client != null) {
				try {
					client.awaitReady();
					var channel = client.getTextChannelsByName(channelName, true).get(0);
					channel.sendMessage(message).complete();
				} catch (InterruptedException e) {
					logger.error(() -> ERROR_WHILE_WAITING_FOR_DISCORD_CLIENT, e);
					Thread.currentThread().interrupt();
				}
			} else {
				logger.error(() -> "Unable to send message to discord channel: client not initialized");
			}
		});
	}

	public CompletableFuture<Void> logout() {
		return CompletableFuture.runAsync(() -> {
			if (client != null) {
				client.shutdownNow();
				logger.info(() -> "Discord bot disconnected: " + client.getStatus().toString());
				client = null;
			} else {
				logger.warn(() -> "Discord bot not connected");
			}
		});
	}

	public void updatePresence(String message) {
		if (client != null) {
			try {
				client.awaitReady();
				var activity = Activity.playing("in " + message);
				client.getPresence().setActivity(message == null ? null : activity);
			} catch (InterruptedException e) {
				logger.error(() -> ERROR_WHILE_WAITING_FOR_DISCORD_CLIENT, e);
				Thread.currentThread().interrupt();
			}
		}
	}

	public void disablePresence() {
		if (client != null) {
			try {
				client.awaitReady();
				client.getPresence().setActivity(null);
			} catch (InterruptedException e) {
				logger.error(() -> ERROR_WHILE_WAITING_FOR_DISCORD_CLIENT, e);
				Thread.currentThread().interrupt();
			}
		} else {
			logger.error(() -> "Unable to disable presence: client not initialized");
		}
	}

}
