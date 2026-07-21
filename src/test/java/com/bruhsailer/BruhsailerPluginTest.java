package com.bruhsailer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Development launcher — NOT a unit test, despite living in src/test.
 * This is the standard RuneLite plugin dev pattern: boot the real client
 * and side-load our plugin into it.
 *
 * Run it with: gradlew run
 * (or right-click this file in IntelliJ and choose Run)
 */
public class BruhsailerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BruhsailerPlugin.class);
		RuneLite.main(args);
	}
}
