package com.ironscape.places;

import com.google.gson.Gson;
import java.io.File;

/**
 * Authoring/debug aid: does a given phrase linkify and resolve? Run like
 * PrintSubIdProbe (classpath = main+test classes + gson + runelite-api +
 * resources). Prints the resolution of every argument, plus the linkified
 * form of a sample sentence.
 */
public final class PlaceLinkProbe
{
	public static void main(String[] args) throws Exception
	{
		java.io.InputStream in = PlaceManager.class.getResourceAsStream("places.json");
		System.out.println("resource stream: " + in);
		if (in != null)
		{
			try (java.io.Reader r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
			{
				com.google.gson.JsonObject root = new com.google.gson.JsonParser().parse(r).getAsJsonObject();
				com.google.gson.JsonObject placesObj = root.getAsJsonObject("places");
				System.out.println("raw places entries: " + placesObj.size()
					+ ", has zmi bank: " + placesObj.has("zmi bank"));
			}
		}
		PlaceManager places = new PlaceManager(new Gson(), new File("no-local-places.json"));
		places.load();
		if (args.length > 0)
		{
			// Each argument: a phrase to resolve, or a sentence to linkify.
			for (String arg : args)
			{
				System.out.println("get(" + arg + ")      = " + places.get(arg));
				System.out.println("getLoose(" + arg + ") = " + places.getLoose(arg));
				System.out.println("linkify:  " + places.linkify(arg));
			}
			return;
		}
		System.out.println("get(zmi bank)        = " + places.get("zmi bank"));
		System.out.println("getLoose(ZMI Bank)   = " + places.getLoose("ZMI Bank"));
		System.out.println("getLoose(Khazard Battlefield) = " + places.getLoose("Khazard Battlefield"));
		System.out.println("linkify: " + places.linkify(
			"Run to ZMI bank and safespot the zamorak warrior until you get a rune scimitar"));
	}
}
