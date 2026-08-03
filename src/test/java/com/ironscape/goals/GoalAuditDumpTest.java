package com.ironscape.goals;

import com.google.gson.Gson;
import com.ironscape.guide.Guide;
import com.ironscape.guide.GuideLoader;
import com.ironscape.guide.GuideVariant;
import com.ironscape.items.ItemTracker;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Not a test of behaviour — a DUMP for tools/audit-goals.mjs: every
 * text-detected item goal with its full alias chain, written to
 * build/goal-audit.tsv on each test run. The Node checker matches the
 * aliases against the real OSRS item list and reports goals that can
 * never resolve (the "cow calf 0/1" class) all at once, instead of one
 * in-game surprise at a time.
 */
public class GoalAuditDumpTest
{
	@Test
	public void dumpGoals() throws Exception
	{
		Guide guide = new GuideLoader(new Gson()).load(GuideVariant.OZIRIS);
		GoalDetector.Goals goals = GoalDetector.detect(guide);
		File out = new File("build/goal-audit.tsv");
		out.getParentFile().mkdirs();
		try (PrintWriter writer = new PrintWriter(out, StandardCharsets.UTF_8))
		{
			for (GoalDetector.ItemGoal goal : goals.getItemGoals())
			{
				writer.println("ITEM\t" + goal.getSub().getId()
					+ "\t" + goal.getQuantity()
					+ "\t" + goal.getItemName()
					+ "\t" + String.join("|", ItemTracker.aliases(goal.getItemName()))
					+ "\t" + goal.getSub().getPlainText().trim()
						.replaceAll("[\\t\\r\\n]+", " "));
			}
		}
	}
}
