package com.ironscape.annotations;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;

/**
 * Loads and saves step annotations. Two layers:
 *
 *  1. BUNDLED — annotations.json shipped inside the plugin (community
 *     contributions, currently empty). Read-only.
 *  2. LOCAL — ~/.runelite/ironscape/annotations.json, written by the
 *     capture button. Overrides bundled per field. This file is
 *     pretty-printed exactly so it can be shared/PR'd upstream.
 *
 * Deliberately NOT stored via ConfigManager: annotations describe the
 * guide, not the player, so they shouldn't switch with RuneLite profiles.
 */
@Slf4j
@Singleton
public class AnnotationManager
{
	private static final int FILE_VERSION = 1;

	private final Gson gson;
	private final File localFile;

	private Map<String, StepAnnotation> bundled = new HashMap<>();
	private Map<String, StepAnnotation> local = new HashMap<>();

	@Inject
	public AnnotationManager(Gson gson)
	{
		this(gson, new File(RuneLite.RUNELITE_DIR, "ironscape/annotations.json"));
	}

	/** Test constructor: point the local file somewhere harmless. */
	AnnotationManager(Gson gson, File localFile)
	{
		// Pretty printing so the local file stays hand-editable and diffable.
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.localFile = localFile;
	}

	public synchronized void load()
	{
		bundled = readBundled();
		local = readLocal();
		log.debug("Annotations loaded: {} bundled, {} local", bundled.size(), local.size());
	}

	/** The step's target location, local file winning over bundled. Null if none. */
	public synchronized StepAnnotation.Target getTarget(String stepId)
	{
		StepAnnotation l = local.get(stepId);
		if (l != null && l.target != null)
		{
			// A cleared tombstone masks a wrong BUNDLED target (the seeded
			// Ardy farming shop pin was 70 tiles off) without touching the
			// read-only bundle; capturing writes a real target over it.
			return Boolean.TRUE.equals(l.target.cleared) ? null : l.target;
		}
		StepAnnotation b = bundled.get(stepId);
		return b == null ? null : b.target;
	}

	/** Where to train while the step's skill requirement is unmet; null if none. */
	public synchronized StepAnnotation.Target getTrainAt(String stepId)
	{
		StepAnnotation l = local.get(stepId);
		if (l != null && l.trainAt != null)
		{
			return l.trainAt;
		}
		StepAnnotation b = bundled.get(stepId);
		return b == null ? null : b.trainAt;
	}

	/** What the training at getTrainAt consumes; null if none. */
	public synchronized String getTrainWith(String stepId)
	{
		StepAnnotation l = local.get(stepId);
		if (l != null && l.trainWith != null)
		{
			return l.trainWith;
		}
		StepAnnotation b = bundled.get(stepId);
		return b == null ? null : b.trainWith;
	}

	/** The step's auto-completion requirement, local winning over bundled. Null if none. */
	public synchronized StepAnnotation.Requirement getRequirement(String stepId)
	{
		StepAnnotation l = local.get(stepId);
		if (l != null && l.requires != null)
		{
			return l.requires;
		}
		StepAnnotation b = bundled.get(stepId);
		return b == null ? null : b.requires;
	}

	/** The step/sub's mid-quest errand chain, local winning over bundled. Empty if none. */
	public synchronized List<StepAnnotation.Errand> getErrands(String annotationId)
	{
		StepAnnotation l = local.get(annotationId);
		if (l != null && l.errands != null && !l.errands.isEmpty())
		{
			return l.errands;
		}
		StepAnnotation b = bundled.get(annotationId);
		return b == null || b.errands == null ? Collections.emptyList() : b.errands;
	}

	/** Chat-menu options to highlight for a step/sub, local over bundled. Empty if none. */
	public synchronized List<String> getDialog(String annotationId)
	{
		StepAnnotation l = local.get(annotationId);
		if (l != null && l.dialog != null && !l.dialog.isEmpty())
		{
			return l.dialog;
		}
		StepAnnotation b = bundled.get(annotationId);
		return b == null || b.dialog == null ? Collections.emptyList() : b.dialog;
	}

	/** Items needed for a step/sub-step, local file winning over bundled. Empty if none. */
	public synchronized List<StepAnnotation.ItemNeed> getItems(String annotationId)
	{
		StepAnnotation l = local.get(annotationId);
		if (l != null && l.items != null && !l.items.isEmpty())
		{
			return l.items;
		}
		StepAnnotation b = bundled.get(annotationId);
		return b == null || b.items == null ? Collections.emptyList() : b.items;
	}

	/**
	 * The network stop this step really means, or null. See
	 * {@link StepAnnotation#travelVia}.
	 */
	public synchronized String getTravelVia(String annotationId)
	{
		StepAnnotation l = local.get(annotationId);
		if (l != null && l.travelVia != null)
		{
			return l.travelVia;
		}
		StepAnnotation b = bundled.get(annotationId);
		return b == null ? null : b.travelVia;
	}

	/**
	 * Is this item one the quest HANDS the player, under any of the given
	 * annotation keys (step and sub)? See {@link StepAnnotation.ItemNeed#granted}.
	 * Names match case-insensitively, exactly as they were seeded.
	 *
	 * @param annotationIds keys to look under; nulls are skipped
	 */
	public synchronized boolean isGranted(String itemName, String... annotationIds)
	{
		if (itemName == null)
		{
			return false;
		}
		for (String annotationId : annotationIds)
		{
			if (annotationId == null)
			{
				continue;
			}
			for (StepAnnotation.ItemNeed need : getItems(annotationId))
			{
				if (Boolean.TRUE.equals(need.granted) && need.name != null
					&& need.name.equalsIgnoreCase(itemName))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** External reference link for a step; null = none. */
	public synchronized StepAnnotation.Link getLink(String annotationId)
	{
		StepAnnotation l = local.get(annotationId);
		if (l != null && l.link != null)
		{
			return l.link;
		}
		StepAnnotation b = bundled.get(annotationId);
		return b == null ? null : b.link;
	}

	/** Method note rendered like a guide NOTE block; null = none. */
	/** The quest this step is a leg of, when the guide never names it. */
	public synchronized String getQuest(String annotationId)
	{
		StepAnnotation l = local.get(annotationId);
		if (l != null && l.quest != null)
		{
			return l.quest;
		}
		StepAnnotation b = bundled.get(annotationId);
		return b == null ? null : b.quest;
	}

	public synchronized String getNote(String annotationId)
	{
		StepAnnotation l = local.get(annotationId);
		if (l != null && l.note != null)
		{
			return l.note;
		}
		StepAnnotation b = bundled.get(annotationId);
		return b == null ? null : b.note;
	}

	/** Why this step can no longer be done; null = it still can. */
	public synchronized String getObsolete(String annotationId)
	{
		StepAnnotation l = local.get(annotationId);
		if (l != null && l.obsolete != null)
		{
			return l.obsolete;
		}
		StepAnnotation b = bundled.get(annotationId);
		return b == null ? null : b.obsolete;
	}

	/** A quest that must be finished before this step can start; null = none. */
	public synchronized String getPrerequisiteQuest(String annotationId)
	{
		StepAnnotation l = local.get(annotationId);
		if (l != null && l.prerequisiteQuest != null)
		{
			return l.prerequisiteQuest;
		}
		StepAnnotation b = bundled.get(annotationId);
		return b == null ? null : b.prerequisiteQuest;
	}

	/** "N of any item from a set" check (warm clothing); null = none. */
	public synchronized StepAnnotation.GearCheck getGearCheck(String annotationId)
	{
		StepAnnotation l = local.get(annotationId);
		if (l != null && l.gearCheck != null)
		{
			return l.gearCheck;
		}
		StepAnnotation b = bundled.get(annotationId);
		return b == null ? null : b.gearCheck;
	}

	/**
	 * Every step id with completion requirements — ALL entries of a step's
	 * list must be met. A step's `requiresAll` wins over its single
	 * `requires`; the local file wins over bundled per step.
	 */
	public synchronized Map<String, List<StepAnnotation.Requirement>> allRequirements()
	{
		Map<String, List<StepAnnotation.Requirement>> out = new HashMap<>();
		bundled.forEach((id, a) -> {
			List<StepAnnotation.Requirement> requirements = effectiveRequirements(a);
			if (requirements != null)
			{
				out.put(id, requirements);
			}
		});
		local.forEach((id, a) -> {
			List<StepAnnotation.Requirement> requirements = effectiveRequirements(a);
			if (requirements != null)
			{
				out.put(id, requirements);
			}
		});
		return out;
	}

	private static List<StepAnnotation.Requirement> effectiveRequirements(StepAnnotation annotation)
	{
		if (annotation.requiresAll != null && !annotation.requiresAll.isEmpty())
		{
			return annotation.requiresAll;
		}
		return annotation.requires == null ? null : Collections.singletonList(annotation.requires);
	}

	/**
	 * A guide refresh gave some edited steps new ids (see GuideManifest):
	 * re-key the LOCAL annotations so captured targets survive the edit.
	 * Bundled annotations are read-only and stay put — an orphaned
	 * bundled key is harmless and gets fixed at the next bundle
	 * regeneration. Returns how many annotations moved.
	 */
	public synchronized int remapIds(Map<String, String> remap)
	{
		if (remap.isEmpty() || local.isEmpty())
		{
			return 0;
		}
		Map<String, StepAnnotation> remapped = new HashMap<>();
		int moved = 0;
		for (Map.Entry<String, StepAnnotation> entry : local.entrySet())
		{
			String newKey = com.ironscape.guide.GuideManifest.remapId(entry.getKey(), remap);
			if (newKey == null)
			{
				// The clause this annotation pointed at was edited away.
				// Unlike a progress tick, a captured target is real work —
				// keep it under the old key rather than deleting it.
				newKey = entry.getKey();
			}
			if (!newKey.equals(entry.getKey()))
			{
				moved++;
			}
			// merge() would need field-level rules; last-in wins is fine
			// because a collision means the target key already had data.
			remapped.putIfAbsent(newKey, entry.getValue());
		}
		if (moved > 0)
		{
			local = remapped;
			saveLocal();
		}
		return moved;
	}

	/** Called by the capture button: remember where this step happens. */
	public synchronized void setTarget(String stepId, WorldPoint point)
	{
		setTarget(stepId, point, false);
	}

	/** Capture variant: safespot targets get the floating "Safespot" label. */
	public synchronized void setTarget(String stepId, WorldPoint point, boolean safespot)
	{
		StepAnnotation annotation = local.computeIfAbsent(stepId, id -> new StepAnnotation());
		StepAnnotation.Target target = new StepAnnotation.Target();
		target.x = point.getX();
		target.y = point.getY();
		target.plane = point.getPlane();
		target.safespot = safespot ? Boolean.TRUE : null;
		annotation.target = target;
		saveLocal();
	}

	/** Is the EFFECTIVE target (local over bundled) flagged as a safespot? */
	public synchronized boolean isSafespotTarget(String stepId)
	{
		StepAnnotation.Target target = getTarget(stepId);
		return target != null && Boolean.TRUE.equals(target.safespot);
	}

	/** What a right-click "remove captured location" actually did. */
	public enum ClearResult
	{
		/** Local capture deleted; a bundled pin (if any) shows again. */
		REMOVED_LOCAL,
		/** No local capture — the BUNDLED pin was masked with a tombstone. */
		MASKED_BUNDLED,
		/** Nothing visible to remove. */
		NOTHING
	}

	/**
	 * Right-click on the capture button. Two-stage: the first remove
	 * forgets the LOCAL capture (an accidental ⌖ click writes a wrong
	 * target), falling back to any bundled pin; removing again — or
	 * removing when only a bundled pin shows — masks the bundled pin with
	 * a `cleared` tombstone, because a wrong SEEDED pin (the Ardy farming
	 * shop was 70 tiles off) must be removable in-game too. Capturing a
	 * new location replaces the tombstone.
	 */
	public synchronized ClearResult clearTarget(String stepId)
	{
		StepAnnotation l = local.get(stepId);
		StepAnnotation b = bundled.get(stepId);
		boolean hasBundled = b != null && b.target != null;
		if (l != null && l.target != null)
		{
			if (Boolean.TRUE.equals(l.target.cleared))
			{
				return ClearResult.NOTHING; // already tombstoned
			}
			l.target = null;
			if (!hasBundled)
			{
				saveLocal();
				return ClearResult.REMOVED_LOCAL;
			}
			// fall through: local capture gone, mask the bundled pin too —
			// the player is standing there saying "this spot is wrong".
		}
		if (!hasBundled)
		{
			return ClearResult.NOTHING;
		}
		StepAnnotation annotation = local.computeIfAbsent(stepId, id -> new StepAnnotation());
		StepAnnotation.Target tombstone = new StepAnnotation.Target();
		tombstone.cleared = true;
		annotation.target = tombstone;
		saveLocal();
		return ClearResult.MASKED_BUNDLED;
	}

	/**
	 * One bundled file per guide corpus. Step ids are content hashes, so
	 * the key spaces can't collide and a flat merge is safe.
	 */
	private static final String[] BUNDLED_FILES = {"annotations_oziris.json"};

	/** Package-private so tests can inject a bundled corpus. */
	Map<String, StepAnnotation> readBundled()
	{
		Map<String, StepAnnotation> merged = new HashMap<>();
		for (String file : BUNDLED_FILES)
		{
			try (InputStream in = AnnotationManager.class.getResourceAsStream(file))
			{
				if (in == null)
				{
					continue; // a variant without bundled annotations is fine
				}
				merged.putAll(parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
			}
			catch (IOException e)
			{
				log.warn("Could not read bundled annotations from {}", file, e);
			}
		}
		return merged;
	}

	private Map<String, StepAnnotation> readLocal()
	{
		if (!localFile.exists())
		{
			return new HashMap<>();
		}
		try (Reader reader = new FileReader(localFile))
		{
			return parse(reader);
		}
		catch (IOException | RuntimeException e)
		{
			// A corrupt file must not take the plugin down — but don't
			// silently overwrite the player's annotation work either.
			log.warn("Could not read local annotations from {}", localFile, e);
			return new HashMap<>();
		}
	}

	private Map<String, StepAnnotation> parse(Reader reader)
	{
		AnnotationFile file = gson.fromJson(reader, AnnotationFile.class);
		Map<String, StepAnnotation> annotations =
			file == null || file.annotations == null ? new HashMap<>() : file.annotations;
		for (StepAnnotation annotation : annotations.values())
		{
			annotation.items = splitCompoundRunes(annotation.items);
		}
		return annotations;
	}

	/**
	 * The guide writes "all of your mind and air runes" as ONE item, but
	 * it's two — each rune type needs its own have/need check or the name
	 * never matches anything you own. Only single-word types sharing a
	 * trailing "runes" split; prose like "lost tribe brooch and book"
	 * stays one entry.
	 */
	private static final Pattern COMPOUND_RUNES = Pattern.compile(
		"^(?:all (?:of )?(?:your )?)?(\\w+(?:, ?\\w+)*,? and \\w+) runes?$");

	static List<StepAnnotation.ItemNeed> splitCompoundRunes(List<StepAnnotation.ItemNeed> items)
	{
		if (items == null)
		{
			return null;
		}
		List<StepAnnotation.ItemNeed> out = new ArrayList<>(items.size());
		for (StepAnnotation.ItemNeed item : items)
		{
			Matcher m = item.name == null ? null
				: COMPOUND_RUNES.matcher(item.name.toLowerCase(Locale.ROOT).trim());
			if (m == null || !m.matches())
			{
				out.add(item);
				continue;
			}
			for (String type : m.group(1).split(",? and |, ?"))
			{
				StepAnnotation.ItemNeed rune = new StepAnnotation.ItemNeed();
				rune.name = type + " runes";
				rune.quantity = item.quantity;
				out.add(rune);
			}
		}
		return out;
	}

	private void saveLocal()
	{
		File dir = localFile.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs())
		{
			log.warn("Could not create annotation directory {}", dir);
			return;
		}
		AnnotationFile file = new AnnotationFile();
		file.version = FILE_VERSION;
		file.annotations = local;
		try (Writer writer = new FileWriter(localFile))
		{
			gson.toJson(file, writer);
		}
		catch (IOException e)
		{
			log.warn("Could not save annotations to {}", localFile, e);
		}
	}

	/** On-disk shape: {"version": 1, "annotations": {"<stepId>": {...}}} */
	private static class AnnotationFile
	{
		int version;
		Map<String, StepAnnotation> annotations;
	}
}
