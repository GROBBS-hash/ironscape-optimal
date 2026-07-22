package com.bruhsailer.annotations;

import com.bruhsailer.guide.Guide;
import com.bruhsailer.guide.GuideLoader;
import com.bruhsailer.guide.GuideStep;
import com.bruhsailer.guide.GuideVariant;
import com.bruhsailer.guide.SubStep;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * Every key in the BUNDLED annotations file must resolve against the
 * bundled guide as loaded by the real loader (including the clause
 * splitter). Sub-keyed annotations ("stepId:N") are the dangerous case:
 * a splitter change or upstream edit can silently shift which clause an
 * index points at — this test turns that silence into a build failure.
 */
public class BundledAnnotationKeysTest
{
	@Test
	public void bundledAnnotationKeysResolveToRealStepsAndSubs() throws Exception
	{
		Guide guide = new GuideLoader(new Gson()).load(GuideVariant.MAIN);
		Set<String> validIds = new HashSet<>(guide.getStepsById().keySet());
		for (GuideStep step : guide.getAllSteps())
		{
			for (SubStep sub : step.getSubSteps())
			{
				validIds.add(sub.getId());
			}
		}

		JsonObject file;
		try (Reader reader = new InputStreamReader(
			StepAnnotation.class.getResourceAsStream("annotations.json"), StandardCharsets.UTF_8))
		{
			// RuneLite ships an older Gson without JsonParser.parseReader.
			file = new Gson().fromJson(reader, JsonObject.class);
		}

		for (String key : file.getAsJsonObject("annotations").keySet())
		{
			assertTrue("bundled annotation key '" + key + "' matches no step or sub-step "
					+ "in the current guide (upstream edit or splitter change?)",
				validIds.contains(key));
		}
	}
}
