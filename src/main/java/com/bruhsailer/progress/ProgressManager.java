package com.bruhsailer.progress;

import com.bruhsailer.guide.Guide;
import com.bruhsailer.guide.GuideStep;
import com.bruhsailer.guide.GuideVariant;
import com.bruhsailer.guide.SubStep;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Which steps the player has checked off, per guide variant.
 *
 * Persistence: one config value per variant, a comma-joined list of step
 * ids, written through RuneLite's ConfigManager. That means progress lives
 * in the ACTIVE RuneLite profile — switching profile switches progress,
 * and cloud-synced profiles carry it between machines. This replaces the
 * website's browser localStorage.
 */
@Singleton
public class ProgressManager
{
	private static final String CONFIG_GROUP = "bruhsailer";

	private final ConfigManager configManager;

	// In-memory cache so we don't re-parse the config string on every
	// checkbox read. Rebuilt after a profile switch via invalidate().
	private final Map<GuideVariant, Set<String>> cache = new EnumMap<>(GuideVariant.class);

	// Counted xp-drop progress ("train construction (6 chairs...)"): how many
	// drops have been seen per sub-step id. Same cache/persist scheme as
	// completed ids, stored as comma-joined "subId=count" pairs.
	private final Map<GuideVariant, Map<String, Integer>> countedCache = new EnumMap<>(GuideVariant.class);

	@Inject
	public ProgressManager(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	public synchronized boolean isCompleted(GuideVariant variant, String stepId)
	{
		return completedIds(variant).contains(stepId);
	}

	/**
	 * Complete or reset a whole step. The stored set only keeps the parent
	 * id for a fully done step — its sub ids are removed as redundant
	 * (parent completed implies every sub-step completed).
	 */
	public synchronized void setCompleted(GuideVariant variant, GuideStep step, boolean completed)
	{
		Set<String> ids = completedIds(variant);
		boolean changed = completed ? ids.add(step.getId()) : ids.remove(step.getId());
		for (SubStep sub : step.getSubSteps())
		{
			changed |= ids.remove(sub.getId());
		}
		if (changed)
		{
			save(variant, ids);
		}
		if (!completed)
		{
			// Unticking says "this isn't done" — a kept-around full counter
			// would instantly re-tick the step on the next xp drop.
			Map<String, Integer> counts = countedFor(variant);
			boolean countsChanged = false;
			for (SubStep sub : step.getSubSteps())
			{
				countsChanged |= counts.remove(sub.getId()) != null;
			}
			if (countsChanged)
			{
				saveCounted(variant, counts);
			}
		}
	}

	public synchronized boolean isSubCompleted(GuideVariant variant, GuideStep step, SubStep sub)
	{
		Set<String> ids = completedIds(variant);
		return ids.contains(step.getId()) || ids.contains(sub.getId());
	}

	/**
	 * Tick or untick one sub-step. Ticking the last open sub-step collapses
	 * the whole step into "parent completed"; unticking a sub-step of a
	 * completed step expands it back out into individual sub ids first.
	 */
	public synchronized void setSubCompleted(GuideVariant variant, GuideStep step, SubStep sub, boolean completed)
	{
		Set<String> ids = completedIds(variant);
		if (completed)
		{
			ids.add(sub.getId());
			boolean allDone = true;
			for (SubStep s : step.getSubSteps())
			{
				allDone &= ids.contains(s.getId());
			}
			if (allDone)
			{
				ids.add(step.getId());
				for (SubStep s : step.getSubSteps())
				{
					ids.remove(s.getId());
				}
			}
		}
		else
		{
			if (ids.remove(step.getId()))
			{
				for (SubStep s : step.getSubSteps())
				{
					ids.add(s.getId());
				}
			}
			ids.remove(sub.getId());
			// See setCompleted: an unticked sub restarts its xp-drop count.
			Map<String, Integer> counts = countedFor(variant);
			if (counts.remove(sub.getId()) != null)
			{
				saveCounted(variant, counts);
			}
		}
		save(variant, ids);
	}

	/** Xp drops seen so far for a counted sub-step ("train construction (6 chairs...)"). */
	public synchronized int countedProgress(GuideVariant variant, String subId)
	{
		Integer count = countedFor(variant).get(subId);
		return count == null ? 0 : count;
	}

	/** Record one more xp drop for a counted sub-step; returns the new total. */
	public synchronized int incrementCounted(GuideVariant variant, String subId)
	{
		Map<String, Integer> counts = countedFor(variant);
		int total = counts.merge(subId, 1, Integer::sum);
		saveCounted(variant, counts);
		return total;
	}

	/**
	 * How many steps of THIS guide are done. Counts against the guide's
	 * current steps, so ids left over from an older guide version (steps
	 * whose text was edited upstream) don't inflate the number.
	 */
	public synchronized int completedCount(Guide guide)
	{
		int count = 0;
		for (String id : completedIds(guide.getVariant()))
		{
			if (guide.getStepsById().containsKey(id))
			{
				count++;
			}
		}
		return count;
	}

	/** Count of completed steps among the given steps (used per section). */
	public synchronized int completedCount(GuideVariant variant, Iterable<GuideStep> steps)
	{
		Set<String> ids = completedIds(variant);
		int count = 0;
		for (GuideStep step : steps)
		{
			if (ids.contains(step.getId()))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * A guide refresh gave some edited steps new ids (see GuideManifest):
	 * rewrite this profile's saved progress — step ids, their "id:N" sub
	 * ids, and counted xp-drop keys — so ticks survive the edit. Only
	 * touches the ACTIVE profile; call again after a profile switch
	 * (idempotent: already-remapped ids simply aren't in the map).
	 */
	public synchronized void remapIds(GuideVariant variant, Map<String, String> remap)
	{
		if (remap.isEmpty())
		{
			return;
		}
		Set<String> ids = completedIds(variant);
		Set<String> remappedIds = new LinkedHashSet<>();
		boolean idsChanged = false;
		for (String id : ids)
		{
			String mapped = com.bruhsailer.guide.GuideManifest.remapId(id, remap);
			idsChanged |= !mapped.equals(id);
			remappedIds.add(mapped);
		}
		if (idsChanged)
		{
			ids.clear();
			ids.addAll(remappedIds);
			save(variant, ids);
		}

		Map<String, Integer> counts = countedFor(variant);
		Map<String, Integer> remappedCounts = new LinkedHashMap<>();
		boolean countsChanged = false;
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			String mapped = com.bruhsailer.guide.GuideManifest.remapId(entry.getKey(), remap);
			countsChanged |= !mapped.equals(entry.getKey());
			remappedCounts.merge(mapped, entry.getValue(), Math::max);
		}
		if (countsChanged)
		{
			counts.clear();
			counts.putAll(remappedCounts);
			saveCounted(variant, counts);
		}
	}

	/** Call when the active RuneLite profile changes: cached progress belongs to the old profile. */
	public synchronized void invalidate()
	{
		cache.clear();
		countedCache.clear();
	}

	private Set<String> completedIds(GuideVariant variant)
	{
		return cache.computeIfAbsent(variant, this::load);
	}

	private Set<String> load(GuideVariant variant)
	{
		String stored = configManager.getConfiguration(CONFIG_GROUP, key(variant));
		Set<String> ids = new LinkedHashSet<>();
		if (stored != null && !stored.isEmpty())
		{
			Collections.addAll(ids, stored.split(","));
		}
		return ids;
	}

	private void save(GuideVariant variant, Set<String> ids)
	{
		if (ids.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, key(variant));
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, key(variant), String.join(",", ids));
		}
	}

	private static String key(GuideVariant variant)
	{
		return "progress_" + variant.name();
	}

	private Map<String, Integer> countedFor(GuideVariant variant)
	{
		return countedCache.computeIfAbsent(variant,
			v -> decodeCounts(configManager.getConfiguration(CONFIG_GROUP, countedKey(v))));
	}

	private void saveCounted(GuideVariant variant, Map<String, Integer> counts)
	{
		String encoded = encodeCounts(counts);
		if (encoded.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, countedKey(variant));
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, countedKey(variant), encoded);
		}
	}

	/** "s1-14:0=3,s1-14:2=6" -> {s1-14:0: 3, ...}. Malformed entries are dropped. */
	static Map<String, Integer> decodeCounts(String stored)
	{
		Map<String, Integer> counts = new LinkedHashMap<>();
		if (stored == null || stored.isEmpty())
		{
			return counts;
		}
		for (String entry : stored.split(","))
		{
			// lastIndexOf: sub ids contain ':' and '-' but never '='
			int eq = entry.lastIndexOf('=');
			if (eq <= 0)
			{
				continue;
			}
			try
			{
				counts.put(entry.substring(0, eq), Integer.parseInt(entry.substring(eq + 1)));
			}
			catch (NumberFormatException e)
			{
				// drop the corrupted entry, keep the rest
			}
		}
		return counts;
	}

	static String encodeCounts(Map<String, Integer> counts)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(entry.getKey()).append('=').append(entry.getValue());
		}
		return sb.toString();
	}

	private static String countedKey(GuideVariant variant)
	{
		return "counted_" + variant.name();
	}
}
