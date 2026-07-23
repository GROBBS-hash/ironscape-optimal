package com.ironscape.guide;

import lombok.Value;

/**
 * One stretch of step text with uniform styling — the guide authors use
 * bold/color heavily to call out warnings and important numbers, so we keep
 * that formatting for the panel to render.
 *
 * Lombok @Value = immutable data class: private final fields, getters,
 * constructor, equals/hashCode all generated.
 */
@Value
public class TextRun
{
	String text;
	boolean bold;
	boolean italic;
	boolean underline;
	boolean strikethrough;
	/** Hex color like "#8c1d75", or null for default text color. */
	String colorHex;
	/** Hyperlink target, or null if this run is not a link. */
	String url;
}
