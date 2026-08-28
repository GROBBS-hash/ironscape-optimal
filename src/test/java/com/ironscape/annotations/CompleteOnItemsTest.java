package com.ironscape.annotations;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * A step may declare that holding its items is what finishes it. Two data
 * invariants keep that honest.
 *
 * <p>The list IS the completion condition, optional included — that is what
 * stopped Tempoross ticking the moment one of its two rewards appeared. So
 * an item that is merely an errand at the same place ("buy 50 lockpicks
 * while you're here") has to say {@code excludeFromCompletion}, or it holds
 * the step shut until you have it.
 *
 * <p>And an excluded item must never be the ONLY kind on the list: a loop
 * that skips every candidate and then reports success is the shape wave 31
 * found in the level branch, where it completed a sub on no evidence at all.
 * The plugin guards it too; this catches it in the data, where it is
 * readable.
 */
public class CompleteOnItemsTest
{
	@Test
	public void everyDeclaredFinishLineHasSomethingOnIt() throws IOException
	{
		JsonObject annotations = bundled();
		List<String> offenders = new ArrayList<>();
		for (String key : annotations.keySet())
		{
			JsonObject entry = annotations.getAsJsonObject(key);
			if (!entry.has("completeOnItems")
				|| !entry.get("completeOnItems").getAsBoolean())
			{
				continue;
			}
			if (!entry.has("items"))
			{
				offenders.add(key + " declares completeOnItems with no items at all");
				continue;
			}
			JsonArray items = entry.getAsJsonArray("items");
			int counting = 0;
			for (JsonElement item : items)
			{
				JsonObject need = item.getAsJsonObject();
				if (!need.has("excludeFromCompletion")
					|| !need.get("excludeFromCompletion").getAsBoolean())
				{
					counting++;
				}
			}
			if (counting == 0)
			{
				offenders.add(key + " excludes all " + items.size()
					+ " of its items from its own finish line");
			}
		}
		assertTrue("A completeOnItems step whose every item is excluded would"
			+ " complete on no evidence:\n  " + String.join("\n  ", offenders),
			offenders.isEmpty());
	}

	/**
	 * An excluded item must also be optional, so the arrival, purchase and
	 * bank gates skip it as well — and so an older build, which has never
	 * heard of the flag, still renders it muted rather than alarm red.
	 */
	@Test
	public void excludedItemsAreAlsoOptional() throws IOException
	{
		JsonObject annotations = bundled();
		List<String> offenders = new ArrayList<>();
		for (String key : annotations.keySet())
		{
			JsonObject entry = annotations.getAsJsonObject(key);
			if (!entry.has("items"))
			{
				continue;
			}
			for (JsonElement item : entry.getAsJsonArray("items"))
			{
				JsonObject need = item.getAsJsonObject();
				boolean excluded = need.has("excludeFromCompletion")
					&& need.get("excludeFromCompletion").getAsBoolean();
				boolean optional = need.has("optional")
					&& need.get("optional").getAsBoolean();
				if (excluded && !optional)
				{
					offenders.add(key + " / " + need.get("name").getAsString());
				}
			}
		}
		assertTrue("excludeFromCompletion must be written with optional:\n  "
			+ String.join("\n  ", offenders), offenders.isEmpty());
	}

	private static JsonObject bundled() throws IOException
	{
		try (InputStreamReader in = new InputStreamReader(
			CompleteOnItemsTest.class.getResourceAsStream(
				"/com/ironscape/annotations/annotations_oziris.json"),
			StandardCharsets.UTF_8))
		{
			JsonObject root = new Gson().fromJson(in, JsonObject.class);
			return root.has("annotations") ? root.getAsJsonObject("annotations") : root;
		}
	}
}
