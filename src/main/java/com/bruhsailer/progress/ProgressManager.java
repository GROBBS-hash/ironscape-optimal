package com.bruhsailer.progress;

import com.bruhsailer.guide.Guide;
import com.bruhsailer.guide.GuideStep;
import com.bruhsailer.guide.GuideVariant;
import com.bruhsailer.guide.SubStep;
import java.util.Collections;
import java.util.EnumMap;
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
		}
		save(variant, ids);
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

	/** Call when the active RuneLite profile changes: cached progress belongs to the old profile. */
	public synchronized void invalidate()
	{
		cache.clear();
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
}
