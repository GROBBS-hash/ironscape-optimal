package com.bruhsailer.annotations;

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
		File dir = Files.createTempDirectory("bruhsailer-test").toFile();
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
		File dir = Files.createTempDirectory("bruhsailer-test").toFile();
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
	public void recaptureOverwrites() throws Exception
	{
		File dir = Files.createTempDirectory("bruhsailer-test").toFile();
		AnnotationManager manager = new AnnotationManager(new Gson(), new File(dir, "annotations.json"));
		manager.load();

		manager.setTarget("abc123", new WorldPoint(1000, 2000, 0));
		manager.setTarget("abc123", new WorldPoint(3000, 4000, 1));

		StepAnnotation.Target target = manager.getTarget("abc123");
		assertEquals(3000, target.x);
		assertEquals(4000, target.y);
		assertEquals(1, target.plane);
	}
}
