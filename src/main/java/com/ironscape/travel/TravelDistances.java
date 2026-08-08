package com.ironscape.travel;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * How far it really is to WALK from each teleport landing to anywhere on the
 * surface.
 *
 * The first-leg hint used to rank landings by straight-line distance, and a
 * straight line cannot see a mountain. Keep Le Faye is ~145 tiles from the
 * Burthorpe Games Room and ~240 from the Fishing Trawler landing, so Burthorpe
 * won — but Keep Le Faye is on the far side of White Wolf Mountain, so the walk
 * is 476 tiles against the Trawler's 405. The owner took the hint and landed in
 * a corner. The Ardougne wall, every river and every island are the same shape.
 *
 * Real path distance lives in Shortest Path's pathfinder, and the Plugin Hub
 * forbids reaching across classloaders into another plugin. But SP publishes
 * the data its pathfinder runs on — a collision map and its transport tables —
 * so {@code tools/build-travel-distances.mjs} does that search offline, once,
 * at full tile resolution, and bundles the answers as a distance field per
 * landing.
 *
 * <p><b>What the numbers mean.</b> Tiles walked, counting only crossings ANY
 * account can make: no quest gates, no skill gates, no items beyond coins. So a
 * distance here is an upper bound on the real walk — a player with the agility
 * shortcut gets there sooner. That is deliberate. Erring long means the hint
 * suggests a teleport less often, and suggesting one too eagerly is the fault
 * this class exists to fix.
 *
 * <p><b>Resolution.</b> The query point is rounded to a 32-tile cell holding
 * the nearest reachable tile in it. On the guide's own pins that reads ~7%
 * short, which is small against the 40% margin the hint already demands.
 */
@Slf4j
@Singleton
public class TravelDistances
{
	/** No answer: the table is missing, or nothing ungated reaches that cell. */
	public static final int UNKNOWN = -1;

	private static final String RESOURCE = "/com/ironscape/travel/travel_distances.bin.gz";
	private static final int FORMAT_VERSION = 1;
	private static final int UNREACHABLE = 65535;

	private int cell;
	private int minX;
	private int minY;
	private int width;
	private int height;
	private final Map<String, char[]> fields = new HashMap<>();

	public TravelDistances()
	{
		try (InputStream raw = TravelDistances.class.getResourceAsStream(RESOURCE))
		{
			if (raw == null)
			{
				log.warn("No bundled travel distance table; first-leg hints fall back to straight lines");
				return;
			}
			read(new DataInputStream(new GZIPInputStream(raw)));
		}
		catch (IOException | RuntimeException e)
		{
			// Never fatal. Everything downstream treats UNKNOWN as "use the old
			// straight-line metric", so a bad table costs accuracy, not function.
			fields.clear();
			log.warn("Could not read the bundled travel distance table", e);
		}
	}

	/**
	 * The file is little-endian (written by Node), DataInputStream reads
	 * big-endian, hence the byte swaps. Fields are stored as char[] purely
	 * because char is Java's unsigned 16-bit type.
	 */
	private void read(DataInputStream in) throws IOException
	{
		byte[] magic = new byte[4];
		in.readFully(magic);
		if (!"IRTD".equals(new String(magic, StandardCharsets.US_ASCII)))
		{
			throw new IOException("not a travel distance table");
		}
		int version = readInt(in);
		if (version != FORMAT_VERSION)
		{
			throw new IOException("travel distance table is version " + version
				+ ", this build reads version " + FORMAT_VERSION);
		}
		cell = readInt(in);
		minX = readInt(in);
		minY = readInt(in);
		width = readShort(in);
		height = readShort(in);
		int count = readInt(in);
		for (int i = 0; i < count; i++)
		{
			byte[] name = new byte[readInt(in)];
			in.readFully(name);
			char[] cells = new char[width * height];
			for (int c = 0; c < cells.length; c++)
			{
				cells[c] = (char) readShort(in);
			}
			fields.put(new String(name, StandardCharsets.UTF_8), cells);
		}
		log.debug("travel distances: {} landings, {}x{} cells of {} tiles",
			fields.size(), width, height, cell);
	}

	private static int readInt(DataInputStream in) throws IOException
	{
		return Integer.reverseBytes(in.readInt());
	}

	private static int readShort(DataInputStream in) throws IOException
	{
		return Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
	}

	/** True once the bundled table loaded; false means every query answers UNKNOWN. */
	public boolean isLoaded()
	{
		return !fields.isEmpty();
	}

	/** The landing names this table knows, for the test that guards them against the plugin's own list. */
	public java.util.Set<String> origins()
	{
		return fields.keySet();
	}

	/**
	 * Tiles to walk from {@code origin}'s landing spot to {@code target}, or
	 * {@link #UNKNOWN} when no ungated route exists — which is the honest
	 * answer for a target behind a quest gate, and the reason a landing on
	 * Mos Le'Harmless never wins a first leg to the mainland.
	 */
	public int distance(String origin, WorldPoint target)
	{
		char[] field = fields.get(origin);
		if (field == null || target == null)
		{
			return UNKNOWN;
		}
		int index = cellIndex(target);
		if (index < 0)
		{
			return UNKNOWN;
		}
		int d = field[index];
		return d == UNREACHABLE ? UNKNOWN : d;
	}

	/**
	 * Does ANY landing reach this target? The hint uses it to pick a metric
	 * and stay on it: mixing walked tiles for one candidate with a straight
	 * line for the next compares two different things, so when the table
	 * cannot speak about a target at all, every candidate falls back together.
	 */
	public boolean reachable(WorldPoint target)
	{
		int index = cellIndex(target);
		if (index < 0)
		{
			return false;
		}
		for (char[] field : fields.values())
		{
			if (field[index] != UNREACHABLE)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The cell holding this point, or -1 if it is off the surface grid. Plane
	 * is deliberately ignored: a 32-tile cell cannot resolve a staircase, and
	 * the ground floor underneath an upstairs pin is where you walk to anyway.
	 */
	private int cellIndex(WorldPoint p)
	{
		if (!isLoaded())
		{
			return -1;
		}
		int cx = (p.getX() - minX) / cell;
		int cy = (p.getY() - minY) / cell;
		if (p.getX() < minX || p.getY() < minY || cx >= width || cy >= height)
		{
			return -1;
		}
		return cy * width + cx;
	}
}
