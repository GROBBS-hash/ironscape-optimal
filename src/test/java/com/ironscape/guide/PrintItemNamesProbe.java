package com.ironscape.guide;

import com.ironscape.goals.GoalDetector;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;

/**
 * Tooling aid: prints every distinct item name the plugin tracks — text-
 * detected goals (via the REAL GoalDetector) plus bundled annotation item
 * lists — one per line. tools/seed-item-ids.mjs feeds this list to the
 * OSRS Wiki to build the bundled name -> item id map (sprites for
 * untradeables the price-list search can't resolve).
 */
public final class PrintItemNamesProbe
{
	public static void main(String[] args) throws Exception
	{
		TreeSet<String> names = new TreeSet<>();

		Guide guide = new GuideLoader(new Gson()).load(GuideVariant.OZIRIS);
		for (GoalDetector.ItemGoal goal : GoalDetector.detect(guide).getItemGoals())
		{
			names.add(goal.getItemName());
		}

		try (Reader reader = new InputStreamReader(
			PrintItemNamesProbe.class.getResourceAsStream(
				"/com/ironscape/annotations/annotations_oziris.json"), StandardCharsets.UTF_8))
		{
			JsonObject file = new Gson().fromJson(reader, JsonObject.class);
			for (Map.Entry<String, JsonElement> entry : file.getAsJsonObject("annotations").entrySet())
			{
				JsonElement items = entry.getValue().getAsJsonObject().get("items");
				if (items == null)
				{
					continue;
				}
				for (JsonElement item : items.getAsJsonArray())
				{
					names.add(item.getAsJsonObject().get("name").getAsString().toLowerCase());
				}
			}
		}

		names.forEach(System.out::println);
	}
}
