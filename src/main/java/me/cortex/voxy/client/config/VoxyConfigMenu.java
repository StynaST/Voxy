package me.cortex.voxy.client.config;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.embeddedt.embeddium.impl.gui.framework.TextComponent;
import org.taumc.celeritas.api.OptionGUIConstructionEvent;
import org.taumc.celeritas.api.options.OptionIdentifier;
import org.taumc.celeritas.api.options.control.ControlValueFormatter;
import org.taumc.celeritas.api.options.control.CyclingControl;
import org.taumc.celeritas.api.options.control.SliderControl;
import org.taumc.celeritas.api.options.control.TickBoxControl;
import org.taumc.celeritas.api.options.structure.OptionFlag;
import org.taumc.celeritas.api.options.structure.OptionGroup;
import org.taumc.celeritas.api.options.structure.OptionImpact;
import org.taumc.celeritas.api.options.structure.OptionImpl;
import org.taumc.celeritas.api.options.structure.OptionPage;
import org.taumc.celeritas.api.options.structure.OptionStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Voxy's pages in the Celeritas video settings screen.
 *
 * <p>Port note: upstream targets Sodium's declarative config API through a 450 line adapter
 * ({@code SodiumConfigBuilder}, deleted here). Celeritas' options API is the older, much smaller
 * Sodium/Embeddium one -- pages hold groups hold options, and an option is a binding plus a control
 * -- and everything the adapter existed to express collapses into it:
 *
 * <ul>
 *   <li><b>Post-change runners</b> become side effects in the binding's setter, which Celeritas
 *       calls when the player applies changes. Upstream additionally suppresses a runner when a
 *       conflicting option changed in the same apply; the redundant work here (a thread-count
 *       update behind a renderer reload) is cheap enough not to be worth reproducing.</li>
 *   <li><b>Enabler dependency graphs</b> become {@code setEnabledPredicate}. One behavioural
 *       difference: those predicates read the <i>applied</i> config, not the pending screen state,
 *       so dependent options grey out after Apply rather than on the spot.</li>
 *   <li><b>Flags</b> map onto {@link OptionFlag} directly.</li>
 * </ul>
 */
public class VoxyConfigMenu {
    public static final OptionIdentifier<Void> GENERAL = OptionIdentifier.create("voxy", "general");
    public static final OptionIdentifier<Void> RENDERING = OptionIdentifier.create("voxy", "rendering");

    private static final OptionStorage<VoxyConfig> STORAGE = new OptionStorage<>() {
        @Override
        public VoxyConfig getData() {
            return VoxyConfig.CONFIG;
        }

        @Override
        public void save() {
            VoxyConfig.CONFIG.save();
        }
    };

    public static void register() {
        if (!VoxyCommon.isAvailable()) return;//Dont even register the config if its not avalible

        OptionGUIConstructionEvent.BUS.addListener(event->{
            event.addPage(generalPage());
            event.addPage(renderingPage());
        });
    }

    private static OptionPage generalPage() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(booleanOption("voxy.config.general.enabled",
                        (cfg, v)->{
                            cfg.enabled = v;
                            if (v) {
                                //Only load when we are in session, matching upstream
                                if (ClientSessionEvents.inSession && VoxyCommon.getInstance() == null) {
                                    VoxyCommon.createInstance();
                                }
                            } else {
                                var vrsh = IVoxyRenderSystemHolder.getNullableHolder();
                                if (vrsh != null) {
                                    vrsh.voxy$shutdownRenderer();
                                }
                                VoxyCommon.shutdownInstance();
                            }
                            IrisUtil.reload();
                        },
                        cfg->cfg.enabled,
                        null, OptionFlag.REQUIRES_RENDERER_RELOAD))
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(sliderOption("voxy.config.general.serviceThreads",
                        1, Math.max(1, CpuLayout.getCoreCount()), 1,
                        v->TextComponent.literal(Integer.toString(v)),
                        (cfg, v)->{
                            cfg.serviceThreads = v;
                            updateThreads();
                        },
                        cfg->cfg.serviceThreads,
                        VoxyConfigMenu::isEnabled, null))
                .add(booleanOption("voxy.config.general.useSodiumBuilder",
                        (cfg, v)->{
                            cfg.dontUseSodiumBuilderThreads = !v;
                            updateThreads();
                        },
                        cfg->!cfg.dontUseSodiumBuilderThreads,
                        VoxyConfigMenu::isEnabled, OptionFlag.REQUIRES_RENDERER_RELOAD))
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(booleanOption("voxy.config.general.ingest",
                        (cfg, v)->cfg.ingestEnabled = v,
                        cfg->cfg.ingestEnabled,
                        VoxyConfigMenu::isEnabled, null))
                .build());

        return new OptionPage(GENERAL, TextComponent.translatable("voxy.config.general"), groups);
    }

    private static OptionPage renderingPage() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(booleanOption("voxy.config.general.rendering",
                        (cfg, v)->{
                            cfg.enableRendering = v;
                            var vrsh = IVoxyRenderSystemHolder.getNullableHolder();
                            if (vrsh != null) {
                                if (v) {
                                    vrsh.voxy$createRenderer();
                                } else {
                                    vrsh.voxy$shutdownRenderer();
                                }
                            }
                            IrisUtil.reload();
                        },
                        cfg->cfg.enableRendering,
                        VoxyConfigMenu::isEnabled, OptionFlag.REQUIRES_RENDERER_RELOAD))
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(sliderOption("voxy.config.general.subDivisionSize",
                        0, SUBDIV_IN_MAX, 1,
                        v->TextComponent.literal(Integer.toString(Math.round(ln2subDiv(v)))),
                        (cfg, v)->cfg.subDivisionSize = ln2subDiv(v),
                        cfg->subDiv2ln(cfg.subDivisionSize),
                        VoxyConfigMenu::isRenderingEnabled, OptionImpact.HIGH))
                //The value is stored as a float with respect to the size of top level lods, its
                // increment is a fraction with respect to the size of the bottom level lod; it is
                // displayed as a chunk render distance
                .add(sliderOption("voxy.config.general.renderDistance",
                        10/*1*16*/, 64*16, 1,
                        v->TextComponent.literal(Integer.toString(v*2)),
                        (cfg, v)->{
                            cfg.sectionRenderDistance = ((float) v)/16;
                            var vrs = IVoxyRenderSystemHolder.getNullable();
                            if (vrs != null) {
                                vrs.setRenderDistance(cfg.sectionRenderDistance);
                            }
                        },
                        cfg->Math.round(cfg.sectionRenderDistance*16),
                        VoxyConfigMenu::isRenderingEnabled, OptionImpact.MEDIUM))
                .build());

        //Upstream disables this group while Iris shaders are on. IrisUtil always reports absent on
        // 1.12.2, so the predicate is kept for shape and currently never disables anything.
        BooleanSupplier shaderAllows = ()->isRenderingEnabled() && !IrisUtil.irisShadersEnabledInConfig();
        groups.add(OptionGroup.createBuilder()
                .add(booleanOption("voxy.config.general.environmental_fog",
                        (cfg, v)->cfg.useEnvironmentalFog = v,
                        cfg->cfg.useEnvironmentalFog,
                        shaderAllows, OptionFlag.REQUIRES_RENDERER_RELOAD))
                .add(OptionImpl.createBuilder(SSAO.SSAOMode.class, STORAGE)
                        .setId(OptionIdentifier.create("voxy", "ssao_mode", SSAO.SSAOMode.class))
                        .setName(TextComponent.translatable("voxy.config.general.ssao_mode"))
                        .setTooltip(TextComponent.translatable("voxy.config.general.ssao_mode.tooltip"))
                        .setControl(option->new CyclingControl<>(option, SSAO.SSAOMode.class))
                        .setBinding((cfg, v)->cfg.setSSAOMode(v), VoxyConfig::getSSAOMode)
                        //TODO make it so on igpus this is high
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setEnabledPredicate(shaderAllows)
                        .build())
                .build());

        return new OptionPage(RENDERING, TextComponent.translatable("voxy.config.rendering"), groups);
    }

    private static boolean isEnabled() {
        return VoxyConfig.CONFIG.enabled;
    }

    private static boolean isRenderingEnabled() {
        return VoxyConfig.CONFIG.enabled && VoxyConfig.CONFIG.enableRendering;
    }

    private static void updateThreads() {
        var instance = VoxyCommon.getInstance();
        if (instance != null) {
            instance.updateDedicatedThreads();
        }
    }

    private static OptionImpl<VoxyConfig, Boolean> booleanOption(String translationKey,
                                                                 BiConsumer<VoxyConfig, Boolean> setter,
                                                                 Function<VoxyConfig, Boolean> getter,
                                                                 BooleanSupplier enabled,
                                                                 OptionFlag flag) {
        var builder = OptionImpl.createBuilder(boolean.class, STORAGE)
                .setId(OptionIdentifier.create("voxy", idOf(translationKey), Boolean.class))
                .setName(TextComponent.translatable(translationKey))
                .setTooltip(TextComponent.translatable(translationKey + ".tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding(setter, getter);
        if (enabled != null) {
            builder.setEnabledPredicate(enabled);
        }
        if (flag != null) {
            builder.setFlags(flag);
        }
        return builder.build();
    }

    private static OptionImpl<VoxyConfig, Integer> sliderOption(String translationKey,
                                                                int min, int max, int step,
                                                                ControlValueFormatter formatter,
                                                                BiConsumer<VoxyConfig, Integer> setter,
                                                                Function<VoxyConfig, Integer> getter,
                                                                BooleanSupplier enabled,
                                                                OptionImpact impact) {
        var builder = OptionImpl.createBuilder(int.class, STORAGE)
                .setId(OptionIdentifier.create("voxy", idOf(translationKey), Integer.class))
                .setName(TextComponent.translatable(translationKey))
                .setTooltip(TextComponent.translatable(translationKey + ".tooltip"))
                .setControl(option->new SliderControl(option, min, max, step, formatter))
                .setBinding(setter, getter);
        if (enabled != null) {
            builder.setEnabledPredicate(enabled);
        }
        if (impact != null) {
            builder.setImpact(impact);
        }
        return builder.build();
    }

    /**
     * Option ids are derived from the translation key so they stay unique without a second list.
     *
     * <p>Celeritas interns {@link OptionIdentifier}s on {@code modId:path} alone and throws if the
     * same pair is ever created with a different value type. Taking only the last key segment put
     * {@code voxy.config.general.rendering} at path {@code rendering}, which is also the id of the
     * rendering *page* ({@link #RENDERING}, a {@code Void} identifier) -- opening video settings
     * then died with "OptionIdentifier 'voxy:rendering' created with differing class type". So the
     * whole key below {@code voxy.config.} becomes the path, which keeps option ids in their own
     * {@code <page>/<option>} space and can never collide with a page id.
     */
    private static String idOf(String translationKey) {
        String path = translationKey.startsWith(TRANSLATION_PREFIX)
                ? translationKey.substring(TRANSLATION_PREFIX.length())
                : translationKey;
        return path.replace('.', '/').toLowerCase(java.util.Locale.ROOT);
    }

    private static final String TRANSLATION_PREFIX = "voxy.config.";


    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX/SUBDIV_MIN)/Math.log(2);

    //In range is 0->200
    //Out range is 28->256
    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN*Math.pow(2, SUBDIV_CONST*((double)in/SUBDIV_IN_MAX)));
    }

    //In range is ... any?
    //Out range is 0->200
    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double)in)/SUBDIV_MIN)/Math.log(2))/SUBDIV_CONST)*SUBDIV_IN_MAX);
    }
}
