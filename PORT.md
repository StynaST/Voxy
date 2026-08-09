# Voxy 1.12.2 Port — State & Continuation Guide

Porting **Voxy 26.2 Fabric → Minecraft 1.12.2 on Cleanroom Loader**, rendering through **Celeritas**
(the 1.12.2 Sodium/Embeddium backport).

**Status: all 203 source files compile, and `./gradlew build` produces
`build/libs/voxy-0.2.19-beta.jar` (88 MB) with a correct mixin refmap.** Upstream is 227 files /
30,954 LOC.

**Nothing has been run.** The ingest and render hooks are now written (§4a) and the refmap resolves
correctly, but no part of this has executed once. Getting the mod to *launch* is the next milestone,
not getting it to build.

> **Do not trust a single triage pass for "what is left".** Two separate things hide errors:
> javac's default `-Xmaxerrs` is 100 (an earlier revision of this file claimed 5 broken files when
> 14 were), and beyond that, javac stops attributing further files once a round has errors — after
> the last of the model/config errors were fixed, a fresh wave of 42 errors appeared in 16 files
> that had reported clean all along. Triage with `--init-script` adding
> `options.compilerArgs << '-Xmaxerrs' << '5000'`, and re-run until BUILD SUCCESSFUL before
> believing any count.

---

## 1. Environment — non-negotiable facts

| Thing | Value | Why it matters |
|---|---|---|
| Loader | Cleanroom Loader 0.6.6-alpha | Not stock Forge. Stock Forge is Java 8 + LWJGL 2 and **cannot** host this mod. |
| Java | **25** | Voxy uses 45 `record`s, text blocks, `var` in 137 files, pattern `instanceof`, arrow switch. Cleanroom's README: "1.12.2 on Java 25+". No syntax downgrade needed anywhere. |
| LWJGL | **3.4.1**, pinned | From `Cleanroom/gradle.properties`. Voxy needs `GL45C`, `ARBDirectStateAccess`, `ARBSparseBuffer`, `ARBIndirectParameters`, `ARBComputeShader`. |
| Mappings | MCP `stable` / `39` | `ResourceLocation` uses the modern-style `getNamespace()`/`getPath()` names here. |
| Gradle JVM | Java 25, pinned in `gradle.properties` via `org.gradle.java.home` | CurseForgeGradle **requires** JVM ≥ 25; the build fails at configuration time on 21. |

**Cleanroom ships only these LWJGL modules:** `lwjgl` (`unsafe` classifier), `lwjgl-glfw`, `lwjgl-jemalloc`,
`lwjgl-openal`, `lwjgl-opengl`, `lwjgl-stb`, `lwjgl-tinyfd`.
It does **not** ship `lwjgl-lmdb` or `lwjgl-zstd`, which Voxy needs — both are shaded in, pinned to
3.4.1. A version mismatch against the loader's `org.lwjgl.system` core breaks natives at runtime.

**GL namespace:** on Cleanroom, `org.lwjgl` *is* LWJGL 3, so Voxy's GL imports port unchanged.
(Celeritas has a `CeleritasLWJGLRelocationTransformer` that rewrites `org/lwjgl/` → `org/lwjgl3/`,
but that is for stock Forge + lwjgl3ify only. Ignore it here.)

---

## 2. Build

```
cd /home/stella/dev/testing/Voxy-1.12.2
./gradlew compileJava      # fast iteration
./gradlew build            # full: mixin refmap + shadow + reobf
```

Useful triage one-liner:

```
./gradlew compileJava --no-daemon 2>&1 \
  | grep -E "^/home.*error:" | sed -E 's|.*/me/cortex/voxy/([^:]+):.*|\1|' | sort | uniq -c | sort -rn
```

> **Shell gotcha:** the working directory resets between tool calls. Always `cd` with an absolute
> path in the same command as `./gradlew`, or you get empty output that looks like a clean build.

Build is verified working end to end: MC decompiles, ATs apply, mixin refmap generates correct SRG
names, shadow bundles 1,386 dependency entries, reobf produces `build/libs/voxy-0.2.19-beta.jar`.

---

## 3. Reference material on disk

| Path | What |
|---|---|
| `../voxy/` | Upstream Voxy 26.2 Fabric source — the thing being ported |
| `../celeritas-repo/` | **Celeritas upstream git source**, 1387 files. `forge122/` is our target module, `common/` is shared. Use this over the decompile. |
| `../celeritas-src/` | Vineflower decompile of the exact shipped jar. Fallback if the repo commit differs from the jar. |
| `build/rfg/minecraft-src/java/net/minecraft/` | **Decompiled 1.12.2 Minecraft.** This is how to resolve every 1.12.2 API name. Use it constantly. |
| `libs/celeritas-2.4.0-dev.jar` | Repacked from the supplied extracted folder, `compileOnly` |
| `libs/celeritasextra-0.6.3.jar` | Same, for Celeritas Extras |

Repacking Celeritas requires **excluding `module-info.class`** — it references `org.joml`, which
ships as a separate jar, so `jar cfm` otherwise fails with
`Package org.joml missing from ModulePackages class file attribute`.

---

## 4. What is left

Everything compiles. What remains is runtime work, in the order it blocks a first launch:

### 4a. Mixins — written, none run yet

16 mixins are written and registered (`mixins.voxy.json` client, `mixins.voxycommon.json` common):

| Mixin | Target | Role |
|---|---|---|
| `commonImpl…AccessorBlockStateContainer` | `BlockStateContainer` | palette/storage, voxelization hot path |
| `commonImpl…MixinWorld` | `World` | attaches the `WorldIdentifier` (§5) |
| `minecraft.MixinRenderGlobal` | `RenderGlobal` | **owns the `VoxyRenderSystem`** (upstream: `LevelRenderer`); world change + `loadRenderers` reload |
| `minecraft.MixinRenderGlobalIngest` | `RenderGlobal` | border block change → re-ingest section (upstream: `ClientLevel#setBlocksDirty`) |
| `minecraft.MixinChunkProviderClient` | `ChunkProviderClient` | `ICheekyClientChunkCache` |
| `minecraft.MixinFramebufferDepthTexture` | `Framebuffer` | **adds a sampleable depth texture** (see below) |
| `minecraft.MixinEntityRendererFog` | `EntityRenderer` | pushes render-distance fog past the LODs |
| `minecraft.AccessorBlockStairs` / `AccessorEntityRenderer` | | stair base state; lightmap texture |
| `sodium.MixinDefaultChunkRenderer` | Celeritas `DefaultChunkRenderer` | **draws Voxy inside the cutout terrain pass** |
| `sodium.MixinRenderSectionManager` | Celeritas `RenderSectionManager` | ingest on chunk add/remove and on section build |
| `sodium.MixinVisibleChunkCollector` | Celeritas `VisibleChunkCollector` | records which sections vanilla drew |
| `sodium.MixinSimpleWorldRenderer` | Celeritas `SimpleWorldRenderer` | thread pool resize on renderer init |
| `sodium.MixinChunkJobQueue` | Celeritas `ChunkJobQueue` | shares Celeritas' build threads with Voxy's pool |
| `sodium.AccessorChunkTracker` / `AccessorSimpleWorldRenderer` | | chunk status map; section manager |

**Depth is the one structural difference.** Upstream gets a depth texture free — modern render
targets store depth in a texture and Sodium hands Voxy `getDepthTextureView().glId()`. 1.12.2's
`Framebuffer` attaches depth as a **renderbuffer**, which cannot be sampled, so
`MixinFramebufferDepthTexture` allocates an extra depth texture and swaps the framebuffer's depth
attachment to it, leaving the renderbuffer allocated so anything reading or deleting `depthBuffer`
still works. If LOD terrain z-fights or ignores occlusion at runtime, start here.

Deliberately **not** ported: `MixinRenderRegionManager` (Celeritas has no section fade-in to cancel),
`MixinFallbackVisibleChunkCollector` (no such class), `MixinDebugScreenEntryList` (§5, Forge event
instead), `MixinRenderSystem` (§5, proxy post-init instead), the session mixins (§5, FML network
events instead), and all `iris/`, `flashback/`, `nvidium/`, `chunky/` mixins (those mods have no
1.12.2 build).

The three utility mixins are ported too, by different routes:
- **`util/MixinGPUSelect`** → `minecraft.MixinMinecraftStartup`. 1.12.2 creates the window inside
  `Minecraft#init`, so GPU selection and the render-thread priority bump go at **HEAD** of that
  method — before `createDisplay()` — and Voxy's own GL init goes at **RETURN** of the same method,
  which is where upstream's `MixinRenderSystem` fires. That also removed the old assumption that a
  GL context exists by `FMLPostInitializationEvent`; `ClientProxy` now only registers events.
- **`util/MixinBlockableEventLoop`** → `minecraft.MixinUtilRunTask`. 1.12.2 has no
  `BlockableEventLoop`; the equivalent swallow point is `Util#runTask`, which catches the failure
  and logs it fatal. The mixin redirects that log call and rethrows when the cause is a Voxy
  `LoadException`, so a broken load crashes loudly instead of leaving a half-built renderer.
- **`util/MixinGlDebug`** → `client/core/gl/GlDebugCallback`, no mixin. 1.12.2 has no blaze3d
  `GlDebug` to decorate, so Voxy installs its own `KHR_debug` callback under
  `-Dvoxy.glDebug=true`, keeping the useful half: attach a Java stack trace when the GL error came
  from Voxy code, and stay quiet for `Capabilities#testShaderCompilesOk`, which fails on purpose.

### 4b. Smaller gaps

- ~~**Windows CPU enumeration**~~ — done. `GetLogicalProcessorInformationEx` is called through
  LWJGL's `JNI`/`Kernel32` (JNA 4.4 does not expose it and a newer JNA cannot be shaded alongside),
  walking `SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX` by hand. Efficiency cores are marked only when
  the reported efficiency classes actually differ, matching the Linux/sysfs path. **Untested on
  Windows** — the struct offsets are from the Win32 docs, not from a run.
- **`commonImpl/importers/WorldImporter.java`** (532 LOC upstream, the vanilla region-file importer)
  was never copied. Deliberate: the DH/VH importer covers the headline feature, and `VoxyCommands`
  was written without the subcommands that need it (§5). Adding it back means restoring those
  subcommands too.
- **Block entity models.** `bakery/BakedBlockEntityModel.java` is commented out upstream too, and
  1.12.2's `TileEntitySpecialRenderer` has no baked model to read, so this would be new work.

## 5. Port decisions already made (do not re-litigate)

**`WorldIdentifier`** — upstream uses `ResourceKey<Level>` + `ResourceKey<DimensionType>`; 1.12.2 has
neither. Both became `ResourceLocation`:
- `key` = `WorldIdentifier.keyForDimensionId(int)` → `voxy:dimension_<id>`. Uses the **integer
  dimension id**, the only value stable across restarts for modded dimensions. The on-disk storage id
  hashes this, so it must not change between sessions.
- `dimension` = `locationForDimensionType(DimensionType)` → `voxy:<type.getName()>`.

It is constructed in exactly one place (the world mixin) and no other class reads these fields, so
the representation was free to change.

**`Mapper`** (defines the on-disk format) — `BlockState.CODEC` → `NBTUtil.writeBlockState` /
`readBlockState`, which store registry name + properties rather than a raw id, so mappings survive
block-id churn. The datafixer path was **dropped**: 1.12.2's `DataFixer` has no `BLOCK_STATE`
reference, and `readBlockState` already degrades to air for an unregistered block — the same outcome
upstream reaches after its fixer fails, and the caller's "air ⇒ corrupt ⇒ resave" flow is preserved.

**Air test** — `BlockState#isAir()` has no position-independent 1.12.2 equivalent
(`Block#isAir` needs world+pos). Use `Mapper.isAir(IBlockState)`, which tests
`getMaterial() == Material.AIR`. Correctly covers modded air blocks.

**Iris — removed.** No 1.12.2 backport exists; the shader mod here is OptiFine, whose pipeline is
unlike Iris. `IrisUtil` reports absence and keeps the exact public contract (upstream already guarded
every call behind `IRIS_INSTALLED`). `IrisVoxyRenderPipeline` and `client/iris/` were deleted — that
pipeline was written against Iris shader-pack internals, not anything portable.
`IrisUtil.SHADER_MOD_PRESENT` (backed by Celeritas `ShaderModBridge`) is exposed as a hook for a
future OptiFine pipeline, deliberately **not** wired into the Iris flags, which promise Iris
semantics and would otherwise lie.

**`DHImporter` (Vintage Horizons / Distant Horizons import) — done, compiles.** The SQLite schema and
the packed-long column format ported verbatim; VH is a DH 2.x backport and did not change either.
What changed:
- No dynamic registries: `mcWorld.registryAccess().lookupOrThrow(...)` → the global `Biome.REGISTRY`
  and `Block.REGISTRY`. `Block.REGISTRY` is *defaulted*, so a missing block resolves silently to air —
  lookups go through `containsKey` first, or the "could not find block" warning never fires.
- `bottomOfWorld` is hard 0 and world height is `World#getHeight()` (256): 1.12.2 has no negative Y,
  and DH stores min-height relative to world min, so the stored values are already absolute.
- **Block-state strings.** DH encodes an entry as `<biome>_DH-BSW_<block>_STATE_{name:value}{…}`.
  Upstream matches it by serializing every possible state of the block and string-comparing. That
  needs our serialization to be byte-identical to the writer's, which is not knowable for VH, so the
  port instead parses the `{name:value}` pairs and applies them one at a time onto
  `block.getDefaultState()`. A value matches on either `IProperty#getName(value)` or
  `value.toString()` (they differ for property types that do not override `toString`), and an
  unknown property or value warns and leaves that property at its default instead of dropping the
  whole state.
- Two upstream bugs fixed rather than ported:
  `stream.read(col, 0, cl*8)` → `readFully` (XZ and the ByteBuffer stream both return short reads),
  and the zstd slurp loop, whose `while (IOUtils.read(channel, buf) == 0)` exits as soon as the
  buffer fills, truncating any frame over 8196 bytes. The replacement grows on full and stops at EOF,
  and no longer needs commons-io (Forge bundles 2.5, which is old enough to be worth avoiding).
  Also added: `rs.next()` before reading columns, and a null check on `tasks.poll()`.

**`ClientImportManager` — done.** 1.12.2 has no client-side boss bar API: `GuiBossOverlay` keeps its
bar map private and `BossInfoClient`'s only constructor takes a packet. The port drives the overlay
by feeding it synthetic `SPacketUpdateBossInfo` packets (`ADD` / `UPDATE_PCT` / `UPDATE_NAME` /
`REMOVE`) through its **public** `read()`, so no accessor mixin is needed.

**Entry points — Forge lifecycle, not Fabric entrypoints.** `me.cortex.voxy.Voxy` is the `@Mod`
class; a `@SidedProxy` (`CommonProxy` / `client.ClientProxy`) keeps the GL-touching client code off a
dedicated server's classloading path. `VoxyClient.initVoxyClient()` runs from post-init rather than
upstream's mixin on `RenderSystem#initRenderer` — that injection existed only to beat Iris to
initialization, and by post-init the display, and therefore the GL context `Capabilities` queries,
already exists.

`ClientSessionEvents` is driven by `FMLNetworkEvent.ClientConnectedToServerEvent` /
`ClientDisconnectionFromServerEvent` (both posted on `MinecraftForge.EVENT_BUS`), matching upstream's
fabric `ClientPlayConnectionEvents`. **Do not** switch these to `WorldEvent.Load/Unload`: those also
fire on every dimension change, but a session spans them, and `sessionStart` throws if one is
already open. The disconnect handler checks `inSession` because a failed connection attempt
disconnects without ever having connected.

**`VoxyCommands` — Forge `CommandBase` via `ClientCommandHandler`, DH import only.** 1.12.2 has no
brigadier, so the tree became a hand-rolled `switch` with `getTabCompletions`. The surface is:

```
/voxy reload
/voxy import distant_horizons <path>     (only when DHImporter.HasRequiredLibraries)
/voxy import cancel
/voxy debug verifyTLNChildMask [attemptRepair]
/voxy debug gpuTimings <true|false>
```

The `world`, `bobby`, `raw`, `zip` and `current` subcommands are **gone**: every one is a front end
for `WorldImporter`, which is out of scope. Add them back with it if it is ever ported. Errors are
red chat lines rather than `CommandException`, whose 1.12.2 form wants a translation key.

**Debug screen — `RenderGameOverlayEvent.Text`.** Upstream's `DebugScreenEntries` registry has no
1.12.2 equivalent; the version line and both debug groups are appended to the F3 left column, and
`VoxyDebugScreenEntry` was folded into `DebugEntries`. GPU timing could not follow: upstream keys it
off that one debug entry being enabled, and 1.12.2 has no per-entry toggle, so it is an explicit
`/voxy debug gpuTimings <bool>` flag. Tying it to "is F3 open" would silently pay for GPU queries.

**`MixinWorld` builds the identifier lazily.** Upstream injects into `Level`'s constructor, where the
dimension key, dimension-type holder and seed are all arguments. 1.12.2's `World` constructor has
none of them — they live behind `provider` and `worldInfo`, and `provider` is only fully wired by the
*subclass* constructor that runs afterwards. So the mixin shadows both fields and builds the
identifier on first `voxy$getIdentifier()` call. Unsynchronized on purpose: a race can only produce
two identifiers equal by value, and `WorldIdentifier` is used as a value key.

**Flashback — deleted.** `FlashbackCompat` and `IFlashbackMeta` are gone; Flashback is a modern-only
replay mod, and it was the only source of `VoxyClientInstance`'s `noIngestOverride`, which went with
it. **FREX flawless frames — dropped** for the same reason (Fabric rendering API, entrypoint-driven);
`VoxyClient.isFrexActive()` is a constant `false`.

**Model bakery — rewritten, not ported.** Voxy software-rasterizes each block model into six 16x16
face textures. `SoftwareRasterizer` and its 24-byte vertex layout (pos, meta, uv) are unchanged; only
how quads reach it changed:

- `ReuseVertexConsumer` no longer implements anything. It decodes 1.12.2 `BakedQuad`s, whose vertex
  data is a flat `int[]` laid out by a `VertexFormat`. Position and uv offsets are resolved **from
  the quad's own format**, not assumed to be `DefaultVertexFormats.BLOCK` — Forge lets models declare
  custom formats, and a wrong stride silently rasterizes garbage.
- **Fluids have no baked model on 1.12.2**; `BlockFluidRenderer` generates their geometry. They are
  tesselated into a `BufferBuilder` and the finished vertices read straight back out of its
  `ByteBuffer`. Upstream's trick of intercepting `getBlockTint` to detect tinting does not apply:
  1.12.2 tints fluids through `BlockColors` like any other block, so the same probe covers both.
- **Tinting.** Upstream inspects a state's `BlockTintSource`s. 1.12.2's `BlockColors` keeps its
  provider map private and exposes only `colorMultiplier`, so `ModelFactory` probes that instead:
  -1 means untinted. Biome dependence is detected by handing the provider a fake one-block world
  ({@code BakeBlockAccess}) and watching whether it ever asks for the biome — 1.12.2 providers read
  the biome from the world rather than being given a resolver. Only tint index 0 is probed.
- **No waterlogging** means a non-fluid block never carries a fluid state, so upstream's whole
  "pre-bake the contained fluid first" ordering hack is gone, and the fluid LUT only maps fluids to
  themselves.
- Render layers are **per block** on 1.12.2 (`Block#getRenderLayer`), not per quad, so a model lands
  entirely in one consumer. `anyDarkendTex` is always false: there is one mipmap path for the whole
  atlas, no per-sprite `DARK_CUTOUT` strategy.
- Stairs bake as the block they are cut from, via `AccessorBlockStairs` (`modelState`), matching
  upstream's use of `StairBlock.baseState`.
- Self-culling (`BlockState#skipRendering`) is asked the inverse way: `shouldSideBeRendered` against
  a fake world holding the same state in every position.

**Config GUI — Celeritas options API, `SodiumConfigBuilder` deleted.** Upstream drives Sodium's
declarative config API through a 450-line adapter. Celeritas ships the *older* Sodium/Embeddium
options API, and `VoxyConfigMenu` now targets it directly (~250 lines, no adapter). Mappings:
post-change runners become side effects inside the binding setter; enabler dependency graphs become
`setEnabledPredicate`; flags map onto `OptionFlag`. Two deliberate behaviour differences: enabled
predicates read the *applied* config rather than pending screen state (dependent options grey out
after Apply, not instantly), and upstream's suppression of a runner when a conflicting option
changed in the same apply is not reproduced — the duplicated work is trivial.

Pages register through `OptionGUIConstructionEvent.BUS`. Worked third-party example:
`celeritas-src/jp/s12kuma01/celeritasextra/client/gui/CeleritasExtraGameOptionPages.java`.

**Lang files are `.lang`, not `.json`.** 1.12.2 predates JSON language files;
`assets/voxy/lang/en_us.lang` is the converted upstream `en_us.json`, `key=value` per line.

**Render hookup — `RenderGlobal`, drawn in Celeritas' cutout pass.** Upstream hangs the render
system off `LevelRenderer` and draws from Sodium's `DefaultChunkRenderer`. The 1.12.2 shapes differ:
`RenderGlobal` has no `close()`, so `setWorldAndLoadRenderers` (called with null on disconnect) is
both the world-change and teardown hook, and `loadRenderers` stands in for upstream's
`LevelExtractor#allChanged` reload. Celeritas' `render` has the older Embeddium signature — no fog
parameters, no GPU sampler, and render passes have no `getTarget()` — so fog comes from
`FogParameters.captureFromGameState()` (reading `GlStateManager.fogState`, exactly as Celeritas' own
`GLStateManagerFogService` does) and the framebuffer is Minecraft's own.

**Fog is fixed-function.** Upstream edits the `FogData` returned by the modern `FogRenderer`;
1.12.2's `EntityRenderer#setupFog` programs GL fog directly, so the mixin runs after it and
overwrites the GL state, keeping upstream's "leave very close fog alone" rule (that fog is an
underwater/blindness effect, not a draw-distance limit).

**Packaging — four things the jar needed before it could ever work.** Found by auditing the built
jar, not by running it:

- **Voxy must be a coremod** (`me.cortex.voxy.VoxyCoreMod`, `is_coremod = true`). Two separate
  facts stack up here, and the second one cost a launch to find:

  1. Nothing else in the jar points at the mixin configs — the manifest carries only `FMLAT`, and
     MixinBooter does not scan for `mixins.*.json` by name. It takes them from an
     `IEarlyMixinLoader` (coremod) or an `ILateMixinLoader` (`@MixinLoader`-annotated). With
     neither, **every mixin is silently skipped**. Upstream needs no equivalent because Fabric
     declares mixin configs in `fabric.mod.json`.
  2. A **late** loader is too late for half of Voxy's targets. It runs during FML mod construction,
     but `Minecraft` and `Framebuffer` are created inside `Minecraft#init` *before* mod loading
     starts, `Util` loads early, and `BlockStairs` during vanilla bootstrap. Their mixins are then
     never applied, and **nothing logs a failure** — the config is accepted, the classes were simply
     already transformed. Observed first run: 10 of 18 mixins applied, and because
     `MixinMinecraftStartup` was among the missing, `initVoxyClient()` never ran,
     `VoxyCommon.isAvailable()` stayed false, and the entire mod sat inert behind one line:
     `Not creating renderer due to disabled`.

  To check this in a log: `grep "APPLY mixins.voxy" logs/cleanmix.log` and compare the count against
  the config. Absence is silent, so the count is the only signal.

  `VoxyCoreMod` must not reference Voxy's own classes — coremod plugins load on the launch
  classloader, before the mod classpath exists.
- **`required-after:celeritas`** on the `@Mod` annotation. Half the render path mixes into Celeritas
  classes, so its absence should be a readable FML message, not a `NoClassDefFoundError` mid-load.
- **`clientSideOnly = true`.** Every ported feature is client side; Celeritas is a client mod. A
  dedicated-server entry point would have to relax both this and the dependency.
- **Shaded libraries are relocated** where they can collide. Jedis drags in gson, slf4j, org.json
  and commons-pool2, and Forge 1.12.2 already ships gson — duplicates of those are a classic mod
  conflict. They now live under `me.cortex.voxy.shadow.*`. **rocksdb, lz4/jpountz, sqlite-jdbc and
  the LWJGL modules are deliberately left alone**: all four bind native code whose symbol names
  derive from the java package, so relocating them breaks native loading. xz is left alone too, as
  `DHImporter` probes for it by name.

**Vivecraft VR — removed.** Vivecraft exists for 1.12.2 but as a client fork with no
`org.vivecraft.api.client` surface; that API is modern-only. `ViewportSelector` collapses to the
single-viewport path, structure intact.

**ModMenu — deleted.** Fabric-only.

**`RenderProperties`** — all three flags are constant `false`. Verified across both Celeritas source
trees that nothing calls `glClipControl`, so 1.12.2 is default `[-1,1]` NDC depth, no reverse-Z, and
`useBlockAtlasUVs` was Iris-only. Structure kept: it is what shader defines and depth-func selection
key off, and it is the single place to change if Celeritas ever adopts clip control.

**Biome fidelity is reduced, unavoidably.** Voxy's format stores one biome per 4×4×4 cell. Modern MC
has 4×4×4 biomes so it's exact there; 1.12.2 biomes are 2D and per-column at 1-block resolution, so
`WorldConversionFactory` samples the middle of each 4-wide horizontal cell and replicates down Y.
Fixing this properly would mean changing Voxy's storage format and shader layout.

---

## 6. Traps — each one compiles fine or fails confusingly

**1.12.2 `BitArray` straddles longs; modern `SimpleBitStorage` does not.** An entry can span two
longs (`if (j != k)` in `BitArray#getAt`). Upstream's fast palette unpack assumes non-straddling
packing and walks with a fixed shift — **ported verbatim it compiles and silently decodes garbage.**
`WorldConversionFactory` reads by absolute bit offset and stitches across the boundary. Bit width is
recoverable as `backingLongArray.length / 64` (a section is always 4096 entries).

**Light lives in the section, not a light engine.** `ExtendedBlockStorage#getBlockLight()` /
`getSkyLight()` return NibbleArrays directly, so upstream's whole
`LayerLightSectionStorage.SectionType.LIGHT_AND_DATA` interrogation collapses to a null check plus
`Chunk#isLightPopulated()`. There is no `NibbleArray#copy` — use
`new NibbleArray(array.getData().clone())`.

**Prefer accessor mixins over hand-written ATs.** `@Accessor("storage")` lets the refmap resolve SRG
at build time (verified: produced `field_186021_b`). Hand-writing SRG names into an AT is easy to get
wrong and fails in a way that is hard to attribute. `src/main/resources/voxy_at.cfg` is currently
comment-only and documents the upstream accesswidener→1.12.2 mapping.

**But prefer plain access over both.** `@Shadow` on a *final* field (like `World.provider`) fails at
apply time, and **shadowed fields did not land in the refmap at all** in this project — verified by
dumping `mixins.voxy.refmap.json` from the built jar, where `@Accessor` entries appear and `@Shadow`
field entries do not. A shadow whose SRG name is missing from the refmap breaks at runtime under
Cleanroom, silently in dev. Where the member is public API, cast
`(World)(Object)this` and reference it normally: reobf remaps it like any other call into Minecraft,
and the mixin stays out of the refmap entirely. `MixinWorld` does this — verify with
`javap -c` on the class inside `build/libs/voxy-0.2.19-beta.jar`, which should show `field_73011_w`
and `func_72905_C`, not MCP names.

**Forge 1.12.2 bundles stale libraries that shadow anything newer:**
- **fastutil 7.1.0** — no `it.unimi.dsi.fastutil.ints.IntConsumer` (added in v8; use
  `java.util.function.IntConsumer`), and no primitive `IntSet`/`LongSet#forEach` — they box through
  `Consumer<? super Integer/Long>`, so iterate the primitive iterator explicitly (`NodeManager`,
  `BasicSectionGeometryManager`). Its `Int2ObjectFunction` has `get(int)` but no `apply(int)`, and
  `Long2IntFunction` is **not** a functional interface there (several abstract methods), so it
  cannot take a lambda — `ExpandingObjectAllocationList` uses `java.util.function.IntFunction` and
  `TestNodeManager` declares its own one-method interface. `LongSet` has no `longStream()`.
- **commons-lang3 3.5** — no no-arg `NotImplementedException`, no `org.apache.commons.lang3.stream.Streams`.
- **oshi 1.1 / JNA 4.4** — far older than the APIs upstream calls, and **cannot be shaded newer**
  (same packages). `CpuLayout` reads sysfs instead. Note Voxy already calls Win32 through LWJGL's
  `JNI`/`Kernel32` (see `ThreadUtils`), *not* JNA — that is the pattern for the Windows core
  enumeration that is still missing.

**Celeritas shades JOML** to `org.embeddedt.embeddium.impl.shadow.joml`, while Voxy uses plain
`org.joml`. Conversion is needed at any interop boundary — `client/core/util/JomlConv` copies through
a float array; `IrisUtil`'s captured `ChunkRenderMatrices` is the live example.

**1.12.2 has no projection matrix object.** Upstream reads it off the render state; here it only
exists in GL state, which is also where view bobbing and hurt-tilt end up, so `VoxyRenderSystem`
reads `GL_PROJECTION_MATRIX` back with `glGetFloatv`.

**`GlStateManager` on 1.12.2 tracks no blend equation**, so the blaze3d `_blendEquationSeparate`
call has no cached-state counterpart — only the raw `glBlendEquation` survives. `_blendFuncSeparate`
→ `tryBlendFuncSeparate`, `_disableBlend` → `disableBlend`.

**Per-face shade multipliers are constants**, not a `CardinalLighting` record: 1.0 up, 0.5 down,
0.8 north/south, 0.6 east/west, hardcoded in vanilla's block renderer and reproduced in
`AbstractSectionRenderer`.

---

## 7. Name mapping cheat-sheet (verified against `build/rfg/minecraft-src`)

| Modern | 1.12.2 |
|---|---|
| `BlockState` | `IBlockState` |
| `Blocks.AIR.defaultBlockState()` | `Blocks.AIR.getDefaultState()` |
| `LeavesBlock` | `BlockLeaves` |
| `state.getLightDampening()` | `state.getLightOpacity()` |
| `Level` | `World` |
| `ClientLevel` | `WorldClient` |
| `LevelChunk` | `Chunk` |
| `LevelChunkSection` | `ExtendedBlockStorage` |
| `PalettedContainer` | `BlockStateContainer` (`storage`/`palette` are protected → accessor mixin) |
| `DataLayer` | `NibbleArray` |
| `LightLayer` | `EnumSkyBlock` |
| `Mth` | `MathHelper` |
| `Identifier` / `ResourceLocation` | `net.minecraft.util.ResourceLocation` |
| `SectionPos` | none → `me.cortex.voxy.common.util.SectionPos` (packing reproduced bit-for-bit) |
| `Component.literal(s)` | `new TextComponentString(s)` |
| `NbtIo` / `CompoundTag` | `CompressedStreamTools` / `NBTTagCompound` (`putInt`→`setInteger`, `getCompound`→`getCompoundTag`, `put`→`setTag`) |
| `Minecraft.getInstance()` | `Minecraft.getMinecraft()` |
| `mc.options` / `options.getEffectiveRenderDistance()` | `mc.gameSettings` / `gameSettings.renderDistanceChunks` |
| `mc.level` | `mc.world` |
| `mc.gameRenderer` | `mc.entityRenderer` (lightmap is its private `lightmapTexture`) |
| `mc.getFps()` | `Minecraft.getDebugFPS()` |
| `mc.levelRenderer` | `mc.renderGlobal` |
| `level.getMinSectionY()` / `getMaxSectionY()` | none — worlds are `0..getHeight()` |
| `level.cardinalLighting()` | none — face shade constants (§6) |
| `mc.getModelManager().getBlockStateModelSet().get(state)` | `mc.getBlockRendererDispatcher().getModelForState(state)` |
| `state.getRenderShape() == RenderShape.INVISIBLE` | `state.getRenderType() == EnumBlockRenderType.INVISIBLE` |
| `state.is(BlockTags.LEAVES)` | `state.getBlock() instanceof BlockLeaves` |
| `state.emissiveRendering()` / `getLightEmission()` | `state.getLightValue()` (no emissive flag) |
| `state.skipRendering(state, dir)` | `!state.shouldSideBeRendered(fakeAccess, pos, dir)` |
| `BlockAndTintGetter` | `IBlockAccess` (`me.cortex.voxy.client.core.model.BakeBlockAccess`) |
| `Direction` | `EnumFacing` (`get3DDataValue()`→`getIndex()`, `from3DDataValue`→`byIndex`) |
| `FluidState` / waterlogging | none — a block is a `BlockLiquid` or holds no fluid |
| `TextureAtlas` mip level | `mc.gameSettings.mipmapLevels` (`TextureMap.mipmapLevels` is private) |
| `mc.getTextureManager().getTexture(atlas)` | `mc.getTextureMapBlocks()` |
| `mc.executeIfPossible(r)` / `mc.execute(r)` | `mc.addScheduledTask(r)` |
| `mc.gameDirectory` | `mc.gameDir` |
| `mc.levelExtractor.allChanged()` | `mc.renderGlobal.loadRenderers()` |
| `mc.getUser().getProfileId()` | `mc.getSession().getProfile().getId()` |
| `mc.getSingleplayerServer()` | `mc.getIntegratedServer()` |
| `server.getWorldPath(LevelResource.ROOT)` | `server.getEntityWorld().getSaveHandler().getWorldDirectory()` |
| `mc.gameMode.connection.getServerData()` | `mc.getCurrentServerData()` (`info.ip` → `serverIP`; no `isRealm()` → `mc.isConnectedToRealms()`) |
| `ChatFormatting` | `net.minecraft.util.text.TextFormatting` |
| `FabricLoader.getConfigDir()` | `Loader.instance().getConfigDir()` (a `File`) |
| `LerpingBossEvent` + `getBossOverlay().events` | no client API → feed `SPacketUpdateBossInfo` to `GuiBossOverlay#read` |
| `DebugScreenEntries` | no equivalent → `RenderGameOverlayEvent.Text` |
| fabric client brigadier | `CommandBase` + `ClientCommandHandler.instance.registerCommand` |
| `ClientPlayConnectionEvents` | `FMLNetworkEvent.Client{ConnectedToServer,DisconnectionFromServer}Event` |
| chat | `mc.ingameGUI.getChatGUI().printChatMessage(...)` |
| slf4j | log4j2 (`LogManager.getLogger`) |
| `Biome` registry name | `Biome.REGISTRY.getNameForObject(biome)` |
| `Holder<Biome>` | `Biome` |
| blaze3d `GlStateManager._enableDepthTest()` | `GlStateManager.enableDepth()` |
| `_glUseProgram` | `OpenGlHelper.glUseProgram` |
| `_glBindVertexArray` | `GL30.glBindVertexArray` (1.12.2's GlStateManager predates VAOs) |
| `RandomSupport.mixStafford13` | `me.cortex.voxy.common.util.RandomSupport` (reimplemented, bit-identical) |

**Sodium → Celeritas** (near 1:1, all under `org.embeddedt.embeddium.impl.`):

| Sodium | Celeritas |
|---|---|
| `render.chunk.RenderSectionManager` | same path ✅ |
| `render.chunk.DefaultChunkRenderer` / `ShaderChunkRenderer` | same ✅ |
| `render.chunk.RenderSection` / `region.RenderRegionManager` | same ✅ |
| `render.chunk.map.ChunkTrackerHolder` / `ChunkTracker` | same ✅ |
| `render.viewport.Viewport` / `CameraTransform` | same ✅ |
| `render.chunk.lists.VisibleChunkCollector` | same ✅ |
| `SodiumWorldRenderer` | `SimpleWorldRenderer` (renamed) |
| `BuiltSectionInfo` | `BuiltRenderSectionData` (renamed) |
| `util.FogParameters` | `render.chunk.fog.FogService` — different shape; Voxy uses its own `client.core.rendering.FogParameters` record, converted at the boundary |
| `util.color.ColorSRGB` | `impl.util.color.ColorSRGB` |
| `api.util.ColorARGB` / `ColorMixer` | `api.util.ColorARGB` / `ColorMixer` |
| `api.config.*` | **no mirror** — use `org.taumc.celeritas.api.options.*` |

---

## 8. Suggested order from here

1. ~~**Vintage Horizons importer**~~, ~~**import entry path**~~, ~~**model bakery**~~,
   ~~**config GUI**~~, ~~**ingest and render mixins**~~ — all done (§4a, §5).
2. **First launch — by jar drop, decided.** The RFG build stays; testing is done by dropping
   `build/libs/voxy-0.2.19-beta.jar` into a real Cleanroom instance alongside Celeritas and
   MixinBooter. (Migrating to the unimined-based Cleanroom template for an in-repo `runClient` was
   considered and rejected, §9.)

   What to look for, in the order it will break:
   1. **Mixin apply errors** in the log — search for `Adding [mixins.voxy.json] mixin configuration`
      first; if that line is missing, `VoxyMixinLoader` did not run and nothing else matters.
   2. **`@Inject` target failures** on the Celeritas mixins (`remap = false`, so they are matched by
      literal name and will break if Celeritas' internals moved between versions).
   3. **GL state** — the framebuffer depth texture (§4a) is the least certain piece.
   4. **LOD correctness** — bakery output, then meshing.
3. **Verify ingest without rendering.** `/voxy debug verifyTLNChildMask` walks the stored tree, and
   `/voxy import distant_horizons <path>` fills it without needing a single frame to be correct.
   That splits "storage works" from "rendering works" before either is trusted.
4. `WorldImporter` if the vanilla-region import is wanted back (§4b), and the Windows core
   enumeration wants checking on an actual Windows machine.
5. Optional: trim shaded natives (jar is 88 MB — RocksDB + LWJGL natives for all platforms).

## 9. Known open questions

- **Dev-env `runClient` targets stock Forge**, which is Java 8 + LWJGL 2 and cannot load this mod.
  Cause found: this project is built on **CleanroomMC/ForgeDevEnv** (RetroFuturaGradle), not on
  **CleanroomMC/CleanroomModTemplate**. The Cleanroom template uses a different build system
  entirely — `xyz.wagyourtail.unimined` with a Java 25 toolchain — and gets a working Cleanroom
  `runClient` from:

  ```groovy
  unimined.minecraft {
      version "1.12.2"
      mappings { mcp("stable", "39-1.12") }
      cleanroom {
          accessTransformer "src/main/resources/voxy_at.cfg"
          loader "0.6.6-alpha"
      }
      defaultRemapJar = false
      remap(tasks.named("shadowJar").get())
  }
  ```

  Switching means replacing RFG wholesale: reobf becomes unimined's `remapJar`, and how the mixin
  refmap is produced changes with it. That is a real migration with no way to validate it short of
  launching, so it was **rejected** — the RFG build works and produces a jar, and testing happens by
  dropping that jar into a real Cleanroom instance (§8). Revisit only if in-repo `runClient` becomes
  worth the migration risk.
- Shaders declare `#version 460 core` — needs GL 4.6. Fine on Cleanroom/LWJGL 3.4.1 given adequate
  GPU, but worth confirming on the target machine.
- OptiFine shader support and VR were cut (§5). Both are new work, not ports.
- **Nothing below the compiler has been checked.** First-run suspects, in rough order of risk: the
  framebuffer depth texture (§4a), the bakery's fluid readback (BufferBuilder byte offsets), the
  tint probes, the GL projection readback, and whether Celeritas' `DefaultChunkRenderer#render`
  injection point survives reobf.
- ~~**A modded colour provider could throw during the tint probe.**~~ — handled: `probeTint`
  catches, warns once per block, and treats the state as untinted. A colour that cannot be
  evaluated cannot be baked either, so that is the right answer, not just a guard.
- ~~**Does VH name its database `DistantHorizons.sqlite`?**~~ — no longer matters. Handed a
  directory, the command prefers that name, otherwise takes the single `.sqlite` file in it, and
  says so when there are several.
- ~~**`ClientProxy.postInit` assumes a GL context.**~~ — removed. Voxy's GL init now runs at the
  return of `Minecraft#init`, immediately after the display is created (§4a).
