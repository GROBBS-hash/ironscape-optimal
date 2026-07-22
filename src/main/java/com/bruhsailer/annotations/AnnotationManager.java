package com.bruhsailer.annotations;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *  2. LOCAL — ~/.runelite/bruhsailer/annotations.json, written by the
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
		this(gson, new File(RuneLite.RUNELITE_DIR, "bruhsailer/annotations.json"));
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
			return l.target;
		}
		StepAnnotation b = bundled.get(stepId);
		return b == null ? null : b.target;
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
			String newKey = com.bruhsailer.guide.GuideManifest.remapId(entry.getKey(), remap);
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
		StepAnnotation annotation = local.computeIfAbsent(stepId, id -> new StepAnnotation());
		StepAnnotation.Target target = new StepAnnotation.Target();
		target.x = point.getX();
		target.y = point.getY();
		target.plane = point.getPlane();
		annotation.target = target;
		saveLocal();
	}

	/**
	 * One bundled file per guide corpus: the hand-reviewed BRUHsailer
	 * annotations plus the scraper-generated Oziris ones. Step ids are
	 * content hashes, so the key spaces can't collide and a flat merge
	 * is safe.
	 */
	private static final String[] BUNDLED_FILES = {"annotations.json", "annotations_oziris.json"};

	private Map<String, StepAnnotation> readBundled()
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
		return file == null || file.annotations == null ? new HashMap<>() : file.annotations;
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
