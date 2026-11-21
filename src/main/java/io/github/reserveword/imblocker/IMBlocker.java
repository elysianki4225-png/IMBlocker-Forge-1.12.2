package io.github.reserveword.imblocker;

import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * IMBlocker Forge 1.12.2 — unofficial port of the IMBlocker mod.
 *
 * <p>IMBlocker automatically disables the system input method (IME) when playing
 * and re-enables it when the player is typing in a text field, so CJK input
 * method users do not constantly fight their IME on every key press.
 *
 * <p>This is a client-side mod. Installing it on a dedicated server is harmless:
 * the mod simply never activates (no client classes are available there and all
 * client-only reflection is guarded).
 *
 * <p>Design notes for the 1.12.2 port:</p>
 * <ul>
 *     <li>No mixins and no coremod are used. Focus is detected by polling the
 *         current screen's text fields once per client tick via reflection,
 *         using both MCP names (development environment) and SRG names
 *         (production environment). See {@link McAccess}.</li>
 *     <li>No compile-time dependency on Minecraft or LWJGL classes exists.
 *         Everything that is obfuscation-sensitive is resolved reflectively at
 *         runtime, which makes the produced jar work in development workspaces
 *         and in production without any remapping step.</li>
 *     <li>Input method control is implemented per platform without external
 *         dependencies: {@code fcitx5-remote}/{@code fcitx-remote}/{@code ibus} on Linux.
 *         Windows and macOS are not implemented in this port.
 *         See the platform package.</li>
 * </ul>
 *
 * @author reserveword (original IMBlocker), LitnhJacuzzi (original IMBlocker),
 *         IMBlocker-Forge-1.12.2 contributors (1.12.2 port)
 * @see <a href="https://github.com/reserveword/IMBlocker">Original IMBlocker</a>
 */
@Mod(modid = IMBlocker.MODID, name = IMBlocker.MODNAME, version = IMBlocker.VERSION,
        dependencies = "required-after:forge@[14.23.5.2860,)", acceptedMinecraftVersions = "[1.12.2]",
        clientSideOnly = true)
public class IMBlocker {

    public static final String MODID = "imblocker";
    public static final String MODNAME = "IMBlocker";
    public static final String VERSION = "1.0.0";
    public static final String MC_VERSION = "1.12.2";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.Instance(value = MODID)
    public static IMBlocker INSTANCE;

    /**
     * Pre-initialization: load the configuration file and register the client
     * tick handler that drives the input method state machine.
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File configDir = event.getModConfigurationDirectory();
        File configFile = new File(configDir, MODID + ".cfg");
        IMBlockerConfig.load(configFile);

        LOGGER.info("[{}] IMBlocker for Minecraft 1.12.2 loaded. Config: {}", MODID, configFile.getAbsolutePath());

        // The tick handler decides whether the IME should be usable. Registering
        // it on the common bus is safe: ClientTickEvent only fires on the client.
        MinecraftForge.EVENT_BUS.register(new IMController());
    }

    /**
     * Initialization: warm up the platform input method bridge in the
     * background and register the toggle key binding.
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Resolve reflection handles and pre-start the platform bridge off the
        // main thread so game startup is never blocked by IME integration.
        IMManager.initAsync();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                IMManager.shutdown();
            }
        }, "imblocker-shutdown"));
    }
}
