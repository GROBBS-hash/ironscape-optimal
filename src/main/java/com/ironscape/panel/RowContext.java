package com.ironscape.panel;

import com.ironscape.annotations.AnnotationManager;
import com.ironscape.goals.GoalDetector;
import com.ironscape.guide.GuideVariant;
import com.ironscape.items.ItemTracker;
import com.ironscape.places.PlaceManager;
import com.ironscape.progress.ProgressManager;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.Value;

/**
 * Everything a StepRow needs, bundled so the constructor stays sane.
 * Built once per panel rebuild.
 */
@Value
class RowContext
{
	GuideVariant variant;
	ProgressManager progress;
	AnnotationManager annotations;
	ItemTracker items;
	PlaceManager places;

	/** Text-detected item goals keyed by sub-step id (never null; may be empty). */
	Map<String, List<GoalDetector.ItemGoal>> itemGoals;

	/** sub-id -> html for counted-action badges ("construction 3/9"); may be null. */
	java.util.function.Function<String, String> actionBadge;

	/** sub-id -> item name whose sprite heads the action badge ("Barcrawl
	 * card" next to "stamp 0/1"); null function or null result = no icon. */
	java.util.function.Function<String, String> badgeIcon;

	/** Tells the panel to refresh the progress bar after a tick. */
	Runnable onProgressChanged;

	/** Null when capture buttons are disabled/unwired. */
	CaptureHandler captureHandler;

	/** Right-click on ⌖: forget the LOCAL captured target. Null if unwired. */
	Consumer<String> clearTargetHandler;

	/** Routes to an annotation id's target via Shortest Path. Null if unwired. */
	Consumer<String> navigateHandler;

	/** Routes to a named place via Shortest Path (the step gives quest-name
	 * links their context: landmark vs the quest itself). Null if unwired. */
	java.util.function.BiConsumer<String, com.ironscape.guide.GuideStep> placeNavigateHandler;

	/** Hops to a world number ("world 444" links). Null if unwired. */
	Consumer<Integer> worldHopHandler;
}
