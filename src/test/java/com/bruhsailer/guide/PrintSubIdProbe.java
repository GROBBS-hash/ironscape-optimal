package com.bruhsailer.guide;

import com.google.gson.Gson;

/**
 * Annotation authoring aid: prints the step id and every sub id (with its
 * clause text) for steps whose text contains the given search string —
 * the ids you need when hand-writing an annotation entry (e.g. a varbit
 * checkpoint keyed to "stepId:N").
 *
 * Run from the repo root, classpath = compiled classes + gson +
 * runelite-api + src/main/resources:
 *
 *   java -cp ... com.bruhsailer.guide.PrintSubIdProbe "Client of Kourend"
 */
public final class PrintSubIdProbe
{
	public static void main(String[] args) throws Exception
	{
		String needle = args.length > 0 ? args[0] : "Client of Kourend quest up to";
		Guide guide = new GuideLoader(new Gson()).load(GuideVariant.MAIN);
		for (GuideStep step : guide.getAllSteps())
		{
			if (!step.getPlainText().contains(needle))
			{
				continue;
			}
			System.out.println("step " + step.getId());
			for (SubStep sub : step.getSubSteps())
			{
				System.out.println("  " + sub.getId() + " | " + sub.getPlainText().trim());
			}
		}
	}
}
