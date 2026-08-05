package com.ironscape.annotations;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AnnotationManagerTest
{
	@Test
	public void capturedTargetSurvivesReload() throws Exception
	{
		File dir = Files.createTempDirectory("ironscape-test").toFile();
		File file = new File(dir, "annotations.json");

		AnnotationManager manager = new AnnotationManager(new Gson(), file);
		manager.load();
		assertNull(manager.getTarget("abc123"));

		manager.setTarget("abc123", new WorldPoint(3222, 3218, 0));

		// A fresh manager reading the same file — like a client restart.
		AnnotationManager reloaded = new AnnotationManager(new Gson(), file);
		reloaded.load();
		StepAnnotation.Target target = reloaded.getTarget("abc123");
		assertEquals(3222, target.x);
		assertEquals(3218, target.y);
		assertEquals(0, target.plane);
		assertNull(reloaded.getTarget("otherstep"));
		assertNull(reloaded.getRequirement("abc123"));
	}

	@Test
	public void requiresAllWinsOverSingleRequires() throws Exception
	{
		File dir = Files.createTempDirectory("ironscape-test").toFile();
		File file = new File(dir, "annotations.json");
		Files.write(file.toPath(), (
			"{\"version\":1,\"annotations\":{"
				+ "\"multi\":{\"requires\":{\"skill\":\"PRAYER\",\"level\":43},"
				+ "\"requiresAll\":[{\"skill\":\"CRAFTING\",\"level\":93},{\"skill\":\"COMBAT\",\"level\":100}]},"
				+ "\"single\":{\"requires\":{\"skill\":\"FISHING\",\"level\":56}}"
				+ "}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

		AnnotationManager manager = new AnnotationManager(new Gson(), file);
		manager.load();

		java.util.Map<String, java.util.List<StepAnnotation.Requirement>> all = manager.allRequirements();
		assertEquals(2, all.get("multi").size());
		assertEquals("CRAFTING", all.get("multi").get(0).skill);
		assertEquals("COMBAT", all.get("multi").get(1).skill);
		assertEquals(Integer.valueOf(100), all.get("multi").get(1).level);
		assertEquals(1, all.get("single").size());
		assertEquals("FISHING", all.get("single").get(0).skill);
	}

	@Test
	public void compoundRuneNamesSplitPerType() throws Exception
	{
		File dir = Files.createTempDirectory("ironscape-test").toFile();
		File file = new File(dir, "annotations.json");
		Files.write(file.toPath(), (
			"{\"version\":1,\"annotations\":{"
				+ "\"tele\":{\"items\":["
				+ "{\"name\":\"chronicle\"},"
				+ "{\"name\":\"all of your mind and air runes\"}]},"
				+ "\"quest\":{\"items\":[{\"name\":\"lost tribe brooch and book\"}]}"
				+ "}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

		AnnotationManager manager = new AnnotationManager(new Gson(), file);
		manager.load();

		java.util.List<StepAnnotation.ItemNeed> items = manager.getItems("tele");
		assertEquals(3, items.size());
		assertEquals("chronicle", items.get(0).name);
		assertEquals("mind runes", items.get(1).name);
		assertEquals("air runes", items.get(2).name);
		// prose "and" that isn't a rune list stays one entry
		assertEquals("lost tribe brooch and book", manager.getItems("quest").get(0).name);
		assertEquals(1, manager.getItems("quest").size());
	}

	@Test
	public void recaptureOverwrites() throws Exception
	{
		File dir = Files.createTempDirectory("ironscape-test").toFile();
		AnnotationManager manager = new AnnotationManager(new Gson(), new File(dir, "annotations.json"));
		manager.load();

		manager.setTarget("abc123", new WorldPoint(1000, 2000, 0));
		manager.setTarget("abc123", new WorldPoint(3000, 4000, 1));

		StepAnnotation.Target target = manager.getTarget("abc123");
		assertEquals(3000, target.x);
		assertEquals(4000, target.y);
		assertEquals(1, target.plane);
	}

	@Test
	public void clearRemovesLocalCapture() throws Exception
	{
		File dir = Files.createTempDirectory("ironscape-test").toFile();
		AnnotationManager manager = new AnnotationManager(new Gson(), new File(dir, "annotations.json"));
		manager.load();
		manager.setTarget("abc123", new WorldPoint(1000, 2000, 0));

		assertEquals(AnnotationManager.ClearResult.REMOVED_LOCAL, manager.clearTarget("abc123"));
		assertNull(manager.getTarget("abc123"));
		assertEquals(AnnotationManager.ClearResult.NOTHING, manager.clearTarget("abc123"));
	}

	/** Test double with one bundled target — a wrong seeded pin. */
	private static AnnotationManager withBundledTarget(File file, String stepId)
	{
		StepAnnotation bundled = new StepAnnotation();
		bundled.target = new StepAnnotation.Target();
		bundled.target.x = 2624;
		bundled.target.y = 3300;
		java.util.Map<String, StepAnnotation> corpus = new java.util.HashMap<>();
		corpus.put(stepId, bundled);
		return new AnnotationManager(new Gson(), file)
		{
			@Override
			java.util.Map<String, StepAnnotation> readBundled()
			{
				return corpus;
			}
		};
	}

	@Test
	public void clearMasksWrongBundledPinAndCaptureReplacesIt() throws Exception
	{
		File dir = Files.createTempDirectory("ironscape-test").toFile();
		File file = new File(dir, "annotations.json");
		AnnotationManager manager = withBundledTarget(file, "shop");
		manager.load();
		assertEquals(2624, manager.getTarget("shop").x);

		// Removing with no local capture tombstones the bundled pin...
		assertEquals(AnnotationManager.ClearResult.MASKED_BUNDLED, manager.clearTarget("shop"));
		assertNull(manager.getTarget("shop"));
		assertEquals(AnnotationManager.ClearResult.NOTHING, manager.clearTarget("shop"));

		// ...the mask survives a client restart...
		AnnotationManager reloaded = withBundledTarget(file, "shop");
		reloaded.load();
		assertNull(reloaded.getTarget("shop"));

		// ...and capturing the right spot replaces the tombstone.
		reloaded.setTarget("shop", new WorldPoint(2645, 3360, 0));
		assertEquals(2645, reloaded.getTarget("shop").x);
	}

	@Test
	public void clearOverBundledDropsLocalAndMasksInOneGo() throws Exception
	{
		File dir = Files.createTempDirectory("ironscape-test").toFile();
		AnnotationManager manager = withBundledTarget(new File(dir, "annotations.json"), "shop");
		manager.load();
		manager.setTarget("shop", new WorldPoint(9, 9, 0));

		// The player standing at the wrong pin means "no pin here" — falling
		// back to the (equally wrong) bundled pin would force a second remove.
		assertEquals(AnnotationManager.ClearResult.MASKED_BUNDLED, manager.clearTarget("shop"));
		assertNull(manager.getTarget("shop"));
	}
}
