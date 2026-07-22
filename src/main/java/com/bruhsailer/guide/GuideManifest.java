package com.bruhsailer.guide;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Remembers which steps (by id) the guide contained last time it loaded,
 * and where each one sat.
 *
 * Step ids are hashes of the step's own text, so an upstream text edit
 * gives that step a NEW id — and every checkbox and annotation saved
 * under the old id silently stops matching. Comparing the previous
 * manifest against a freshly loaded guide identifies exactly those
 * edited-in-place steps, so their old ids can be REMAPPED onto the new
 * ones instead of being orphaned.
 *
 * Stored beside the annotations in ~/.runelite/bruhsailer/ (not
 * ConfigManager: the manifest describes the guide, not the player).
 */
@Slf4j
@Singleton
public class GuideManifest
{
	// Version 2 added per-step sub-clause fingerprints ("subs"). Version 1
	// files simply lack that field and fall back to positional sub carry.
	private static final int FILE_VERSION = 2;

	/**
	 * Remap value meaning "this sub clause was edited away — its saved
	 * tick has nothing to attach to". remapId translates it to null.
	 */
	private static final String ORPHANED = "";

	private final Gson gson;
	private final File file;

	@Inject
	public GuideManifest(Gson gson)
	{
		this(gson, new File(RuneLite.RUNELITE_DIR, "bruhsailer/guide_manifest.json"));
	}

	/** Test constructor: point the file somewhere harmless. */
	GuideManifest(Gson gson, File file)
	{
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.file = file;
	}

	/**
	 * Compare the stored manifest against this freshly loaded guide and
	 * return oldId -> newId for every step that was EDITED IN PLACE.
	 * Empty on first run, when nothing changed, or when the file is
	 * unreadable. The caller applies the remap, then calls save().
	 */
	public Map<String, String> reconcile(Guide guide)
	{
		List<ManifestStep> previous = load(guide.getVariant());
		if (previous.isEmpty())
		{
			return new HashMap<>();
		}
		return pairEditedSteps(previous, guide);
	}

	/** Snapshot this guide's step ids and positions for the next load. */
	public void save(Guide guide)
	{
		ManifestFile contents = read();
		VariantManifest variant = new VariantManifest();
		variant.updatedOn = guide.getUpdatedOn();
		variant.steps = new ArrayList<>();
		for (GuideStep step : guide.getAllSteps())
		{
			ManifestStep entry = new ManifestStep();
			entry.id = step.getId();
			entry.chapter = step.getChapterIndex();
			entry.section = step.getSectionIndex();
			entry.step = step.getStepIndex();
			entry.subs = subFingerprints(step);
			variant.steps.add(entry);
		}
		contents.version = FILE_VERSION;
		contents.variants.put(guide.getVariant().name(), variant);

		File dir = file.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs())
		{
			log.warn("Could not create manifest directory {}", dir);
			return;
		}
		try (Writer writer = new FileWriter(file))
		{
			gson.toJson(contents, writer);
		}
		catch (IOException e)
		{
			log.warn("Could not save guide manifest to {}", file, e);
		}
	}

	/**
	 * The pairing rule, deliberately conservative — a WRONG remap silently
	 * corrupts progress, an orphan merely unticks a box:
	 *
	 * Steps are compared per section, index by index, and only when the
	 * section has the SAME number of steps as before (an insertion or
	 * removal shifts every later index, so such sections are skipped
	 * whole). A position pairs up when its old id vanished from the
	 * entire new guide AND its new id never existed in the old one —
	 * i.e. the text at that spot changed. Anything else (reorders,
	 * moves between sections) stays unmatched and is only logged.
	 */
	static Map<String, String> pairEditedSteps(List<ManifestStep> previous, Guide guide)
	{
		Set<String> previousIds = new HashSet<>();
		Map<String, List<ManifestStep>> previousBySection = new LinkedHashMap<>();
		for (ManifestStep step : previous)
		{
			previousIds.add(step.id);
			previousBySection.computeIfAbsent(step.chapter + "." + step.section,
				k -> new ArrayList<>()).add(step);
		}
		Set<String> currentIds = guide.getStepsById().keySet();

		Map<String, String> remap = new LinkedHashMap<>();

		// A step whose id (= text) DIDN'T change can still have different
		// sub-steps than last time: our own clause splitter evolves. Its
		// saved sub ticks are positional, so re-link them by fingerprint
		// exactly like an edited step's. Matching by id needs no positional
		// safety net — the id IS the match.
		for (ManifestStep step : previous)
		{
			GuideStep same = guide.getStepsById().get(step.id);
			if (same != null && step.subs != null && !step.subs.equals(subFingerprints(same)))
			{
				addSubRemap(remap, step, same);
			}
		}

		int orphans = 0;
		for (GuideChapter chapter : guide.getChapters())
		{
			for (GuideSection section : chapter.getSections())
			{
				List<GuideStep> current = section.getSteps();
				if (current.isEmpty())
				{
					continue;
				}
				GuideStep first = current.get(0);
				List<ManifestStep> old = previousBySection.get(
					first.getChapterIndex() + "." + first.getSectionIndex());
				if (old == null || old.size() != current.size())
				{
					continue; // steps added/removed here; indexes shifted
				}
				for (int i = 0; i < current.size(); i++)
				{
					String oldId = old.get(i).id;
					String newId = current.get(i).getId();
					if (oldId.equals(newId))
					{
						continue;
					}
					if (!currentIds.contains(oldId) && !previousIds.contains(newId))
					{
						remap.put(oldId, newId);
						addSubRemap(remap, old.get(i), current.get(i));
					}
					else
					{
						orphans++; // looks like a reorder, not an edit
					}
				}
			}
		}
		if (orphans > 0)
		{
			log.info("Guide update: {} step(s) moved in ways too ambiguous to remap", orphans);
		}
		return remap;
	}

	/**
	 * An edited step's clauses may have shifted, so a sub tick carried by
	 * INDEX can land on the wrong action. When the old manifest recorded
	 * this step's sub fingerprints, match old clauses to new ones by TEXT
	 * instead: every old index gets an explicit entry — either the id of
	 * the identically-worded new clause (first unused, in order, so
	 * duplicate clauses pair up sensibly) or ORPHANED for the clause that
	 * was actually edited. Manifests from before fingerprints existed add
	 * nothing here, leaving remapId's positional fallback in charge.
	 */
	private static void addSubRemap(Map<String, String> remap, ManifestStep old, GuideStep current)
	{
		if (old.subs == null)
		{
			return;
		}
		List<String> currentPrints = subFingerprints(current);
		boolean[] used = new boolean[currentPrints.size()];
		for (int i = 0; i < old.subs.size(); i++)
		{
			String match = ORPHANED;
			for (int j = 0; j < currentPrints.size(); j++)
			{
				if (!used[j] && old.subs.get(i).equals(currentPrints.get(j)))
				{
					used[j] = true;
					match = current.getSubSteps().get(j).getId();
					break;
				}
			}
			// Identity mappings (same step id, same index) would be dead
			// weight — an id the map doesn't know passes through anyway.
			if (!match.equals(old.id + ":" + i))
			{
				remap.put(old.id + ":" + i, match);
			}
		}
	}

	/** Same short content hash as step ids, one per sub clause. */
	private static List<String> subFingerprints(GuideStep step)
	{
		List<String> prints = new ArrayList<>(step.getSubSteps().size());
		for (SubStep sub : step.getSubSteps())
		{
			prints.add(GuideLoader.stepId(sub.getPlainText()));
		}
		return prints;
	}

	/**
	 * Applies a step-id remap to any saved id — including sub-step ids,
	 * which are "stepId:index". Ids the map doesn't know pass through.
	 * Returns null for a sub id whose clause was edited away (see
	 * addSubRemap) — the caller should discard that saved tick.
	 */
	public static String remapId(String id, Map<String, String> remap)
	{
		String exact = remap.get(id);
		if (exact != null)
		{
			return exact.isEmpty() ? null : exact;
		}
		int colon = id.indexOf(':');
		String base = colon < 0 ? id : id.substring(0, colon);
		String mapped = remap.get(base);
		if (mapped == null)
		{
			return id;
		}
		return colon < 0 ? mapped : mapped + id.substring(colon);
	}

	private List<ManifestStep> load(GuideVariant variant)
	{
		VariantManifest stored = read().variants.get(variant.name());
		return stored == null || stored.steps == null ? new ArrayList<>() : stored.steps;
	}

	private ManifestFile read()
	{
		if (!file.exists())
		{
			return new ManifestFile();
		}
		try (Reader reader = new FileReader(file))
		{
			ManifestFile parsed = gson.fromJson(reader, ManifestFile.class);
			if (parsed != null && parsed.variants != null)
			{
				return parsed;
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read guide manifest from {}", file, e);
		}
		return new ManifestFile();
	}

	private static class ManifestFile
	{
		int version;
		Map<String, VariantManifest> variants = new LinkedHashMap<>();
	}

	private static class VariantManifest
	{
		String updatedOn;
		List<ManifestStep> steps;
	}

	static class ManifestStep
	{
		String id;
		int chapter;
		int section;
		int step;
		/**
		 * Content hash of each sub clause, in order — lets an edited
		 * step's sub ticks follow their TEXT to its new position instead
		 * of trusting the index. Null in files written before version 2.
		 */
		List<String> subs;
	}
}
