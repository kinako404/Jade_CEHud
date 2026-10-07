package snownee.jade.addon.cehud;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Shows the real CraftEngine block / furniture described by the CEBlockHud server plugin.
 * Runs before the core plugin's hidden-block filter (-10000) and the datapack block manager (-10010).
 */
@WailaPlugin
public class CEHudPlugin implements IWailaPlugin {
	@Override
	public void registerClient(IWailaClientRegistration registration) {
		registration.addRayTraceCallback(-10020, CEHudClient::onRayTrace);
		registration.addTooltipCollectedCallback(0, CEHudClient::onTooltipCollected);
	}
}
