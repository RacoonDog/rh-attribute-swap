package io.github.racoondog.attributeswap;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;

public class AttributeSwap extends Plugin {
	@Override
	public void onLoad() {
		RusherHackAPI.getModuleManager().registerFeature(new AttributeSwapModule());
	}

	@Override
	public void onUnload() {
	}
}