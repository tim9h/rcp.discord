package dev.tim9h.rcp.discord.bot;

import java.util.List;
import java.util.function.BiPredicate;

import net.dv8tion.jda.api.interactions.commands.build.OptionData;

public record BotCommand(BiPredicate<String, String> action, String description, List<OptionData> options,
		boolean adminOnly) {

	public BotCommand(BiPredicate<String, String> action, String description, List<OptionData> options) {
		this(action, description, options, false);
	}

	public BotCommand(BiPredicate<String, String> action) {
		this(action, null, null);
	}

	public BotCommand(BiPredicate<String, String> action, String description) {
		this(action, description, null);
	}

	public BotCommand(BiPredicate<String, String> action, String description, boolean adminOnly) {
		this(action, description, null, adminOnly);
	}

}
