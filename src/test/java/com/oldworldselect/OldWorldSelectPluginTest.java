package com.oldworldselect;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OldWorldSelectPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OldWorldSelectPlugin.class);
		RuneLite.main(args);
	}
}
