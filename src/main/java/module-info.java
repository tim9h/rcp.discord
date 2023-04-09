module rcp.discord {
	exports dev.tim9h.rcp.discord;

	requires transitive rcp.api;
	requires com.google.guice;
	requires org.apache.logging.log4j;
	requires transitive javafx.controls;
	requires org.apache.commons.lang3;
	requires net.dv8tion.jda;
	requires javafx.graphics;
}