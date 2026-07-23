package com.ironscape.places;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PlaceManagerTest
{
	private PlaceManager freshManager() throws Exception
	{
		File dir = Files.createTempDirectory("ironscape-places").toFile();
		PlaceManager manager = new PlaceManager(new Gson(), new File(dir, "places.json"));
		manager.load();
		return manager;
	}

	@Test
	public void linkifiesKnownNamesCaseInsensitively() throws Exception
	{
		PlaceManager manager = freshManager();
		manager.add("Duke Horacio", new WorldPoint(3210, 3220, 1));

		String html = manager.linkify("Talk to duke horacio upstairs.");
		assertEquals("Talk to <a href='bruh:place:duke+horacio'>duke horacio</a> upstairs.", html);

		// unknown names pass through untouched
		assertEquals("Talk to Hans.", manager.linkify("Talk to Hans."));
	}

	@Test
	public void toleratesApostropheAndAmpersandVariants() throws Exception
	{
		PlaceManager manager = freshManager();
		manager.add("Daddy's Home", new WorldPoint(3241, 3398, 0));
		manager.add("Romeo & Juliet", new WorldPoint(3211, 3424, 0));

		// guide text uses a curly apostrophe; ours is straight
		String html = manager.linkify("complete Daddy’s Home quickly");
		assertEquals("complete <a href='bruh:place:daddy%27s+home'>Daddy’s Home</a> quickly", html);

		// linkify runs on HTML-escaped text where & became &amp;
		String escaped = manager.linkify("progress Romeo &amp; Juliet.");
		assertEquals("progress <a href='bruh:place:romeo+%26+juliet'>Romeo &amp; Juliet</a>.", escaped);

		// lookups normalize the same way
		assertEquals(3241, manager.get("daddy’s home").getX());
	}

	@Test
	public void lookupAndPersistence() throws Exception
	{
		File dir = Files.createTempDirectory("ironscape-places").toFile();
		File file = new File(dir, "places.json");

		PlaceManager manager = new PlaceManager(new Gson(), file);
		manager.load();
		manager.add("Grand Exchange", new WorldPoint(3164, 3487, 0));

		PlaceManager reloaded = new PlaceManager(new Gson(), file);
		reloaded.load();
		WorldPoint point = reloaded.get("grand exchange");
		assertEquals(3164, point.getX());
		assertEquals(3487, point.getY());
		// a name that will never exist in the bundled seed data
		assertNull(reloaded.get("definitely not a real place"));
	}
}
