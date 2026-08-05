package com.ironscape.places;

import com.google.gson.Gson;
import java.io.File;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;

/**
 * The bundled place files must actually LOAD — Gson rejects things
 * lenient JSON parsers accept (a duplicate key killed the WHOLE file
 * once: every link, nav target and arrival area went dead at runtime
 * with only a swallowed warning). This fails the build instead.
 */
public class BundledPlacesLoadTest
{
	@Test
	public void bundledPlacesResolve()
	{
		PlaceManager places = new PlaceManager(new Gson(), new File("no-local-places.json"));
		places.load();
		// A handful of names that must always exist: an ordinary town, a
		// hand-seeded POI, an item source, and a transport network name.
		assertNotNull("places.json failed to load (town missing)", places.get("varrock"));
		assertNotNull("hand-seeded POI missing", places.get("zmi bank"));
		assertNotNull("item_sources.json failed to load", places.get("glarial's pebble"));
		assertNotNull("transport entry missing", places.get("spirit tree"));
	}
}
