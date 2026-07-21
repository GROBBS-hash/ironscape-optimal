package com.bruhsailer.panel;

import com.bruhsailer.annotations.AnnotationManager;
import com.bruhsailer.goals.GoalDetector;
import com.bruhsailer.guide.GuideVariant;
import com.bruhsailer.items.ItemTracker;
import com.bruhsailer.places.PlaceManager;
import com.bruhsailer.progress.ProgressManager;
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

	/** Tells the panel to refresh the progress bar after a tick. */
	Runnable onProgressChanged;

	/** Null when capture buttons are disabled/unwired. */
	CaptureHandler captureHandler;

	/** Routes to an annotation id's target via Shortest Path. Null if unwired. */
	Consumer<String> navigateHandler;

	/** Routes to a named place via Shortest Path. Null if unwired. */
	Consumer<String> placeNavigateHandler;
}
