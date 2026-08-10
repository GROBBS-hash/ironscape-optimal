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

	/** sub-id -> small skill icon for level/counted badges ("magic 33/42"
	 * gets the Magic star); null function or null result = no icon. */
	java.util.function.Function<String, java.awt.image.BufferedImage> skillIcon;

	/**
	 * sub-id -> the errand chain's stage items in order, each NEEDED / HELD
	 * / SPENT. DISPLAY ONLY: these never become annotation items, because
	 * annotationItemsCarried (the arrival gate) and bankFirstTarget read
	 * those, and changing what gates an arrival is not what showing an
	 * icon should do. Null function or null result = no stage badges.
	 */
	java.util.function.Function<String, java.util.LinkedHashMap<String, String>> errandStages;

	/**
	 * sub-id -> true when NOTHING can ever tick this step automatically: no
	 * detector claims it, it carries no varbit/varp checkpoint, and it has
	 * no errand chain to finish. 139 steps guide-wide are in that state and
	 * most of them are genuinely advice ("bank everything", "use
	 * Authenticator"), but meeting one unwarned reads as a broken plugin
	 * every single time. Saying so is cheaper than being asked.
	 * Null function = don't mark anything.
	 */
	java.util.function.Predicate<String> manualOnly;

	/** Tells the panel to refresh the progress bar after a tick. */
	Runnable onProgressChanged;

	/** Null when capture buttons are disabled/unwired. */
	CaptureHandler captureHandler;

	/** "Capture as safespot" in the ⌖ right-click menu. Null if unwired. */
	CaptureHandler safespotCaptureHandler;

	/** Right-click on ⌖: forget the LOCAL captured target. Null if unwired. */
	Consumer<String> clearTargetHandler;

	/** Routes to an annotation id's target via Shortest Path. Null if unwired. */
	Consumer<String> navigateHandler;

	/** Routes to a named place via Shortest Path (the step gives quest-name
	 * links their context: landmark vs the quest itself). Null if unwired. */
	java.util.function.BiConsumer<String, com.ironscape.guide.GuideStep> placeNavigateHandler;

	/** Hops to a world number ("world 444" links). Null if unwired. */
	Consumer<Integer> worldHopHandler;

	/**
	 * Quest name -> the step whose task IS that quest; null when no step is
	 * tagged with it. Feeds the "go and do this first" button on a step with
	 * a prerequisiteQuest.
	 *
	 * Resolved by NAME rather than by the step numbers in the guide's own
	 * notes: those were written against the original numbering and do not map
	 * onto ours by any constant offset (one note calls our step 281 "278",
	 * another refers to "step 1.1.145a" entirely differently).
	 */
	java.util.function.Function<String, com.ironscape.guide.GuideStep> questStep;

	/**
	 * Scroll the panel to whatever the current step now is, and re-point the
	 * route — what the Resume button does.
	 *
	 * Moving the player's POSITION changes nothing on screen by itself: no
	 * checkbox flips, no card restyles, so a jump looked like a dead click
	 * even though it had worked. Ticking a box is self-evidencing; moving
	 * the position is not, and needs to show its own result.
	 */
	Runnable jumpToCurrentHandler;
}
