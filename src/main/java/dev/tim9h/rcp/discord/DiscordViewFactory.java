package dev.tim9h.rcp.discord;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.google.inject.Inject;

import dev.tim9h.rcp.spi.CCard;
import dev.tim9h.rcp.spi.CCardFactory;

public class DiscordViewFactory implements CCardFactory {

	public static final String SETTING_TOKEN = "discord.bot.token";

	static final String SETTING_CHANNEL = "discord.bot.notificationchannel";

	public static final String SETTING_DISCORD_USERTAG = "discord.privileged.user.tag";

	public static final String SETTING_MODES = "core.modes";

	@Inject
	private DiscordView discordView;

	@Override
	public String getId() {
		return "discord";
	}

	@Override
	public CCard createCCard() {
		return discordView;
	}

	@Override
	public Map<String, String> getSettingsContributions() {
		Map<String, String> settings = new HashMap<>();
		settings.put(SETTING_CHANNEL, "general");
		settings.put(SETTING_TOKEN, StringUtils.EMPTY);
		settings.put(SETTING_DISCORD_USERTAG, StringUtils.EMPTY);
		return settings;
	}

}
