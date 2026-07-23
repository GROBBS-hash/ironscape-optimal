package com.ironscape.panel;

import java.util.function.Consumer;

/**
 * Bridge between a capture button (Swing) and the game client.
 * Implemented by the plugin, because reading the player's position has to
 * happen on the game's client thread — not on the Swing thread.
 */
public interface CaptureHandler
{
	/**
	 * Capture the player's current location as the target of a step or
	 * sub-step.
	 *
	 * @param annotationId the step id or sub-step id the location belongs to
	 * @param onDone called back ON THE SWING THREAD with true if a location
	 *               was saved, false if not possible (e.g. not logged in)
	 */
	void capture(String annotationId, Consumer<Boolean> onDone);
}
