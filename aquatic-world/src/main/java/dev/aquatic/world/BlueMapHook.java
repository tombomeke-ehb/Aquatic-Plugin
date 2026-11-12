package dev.aquatic.world;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.IslandStatus;
import dev.aquatic.util.Text;
import org.bukkit.World;

import java.util.function.Consumer;

/**
 * Optional BlueMap integration: a toggleable "Islands" marker set with a circle and a label per island.
 * Only instantiated after checking that the BlueMap plugin is enabled.
 */
final class BlueMapHook {

    private static final String SET_ID = "aquatic-islands";

    private final IslandsModule module;
    private final Consumer<BlueMapAPI> onEnable;
    private final Consumer<BlueMapAPI> onDisable;
    private volatile BlueMapAPI api;

    BlueMapHook(IslandsModule module) {
        this.module = module;
        this.onEnable = a -> {
            api = a;
            module.requestMarkerRefresh();
        };
        this.onDisable = a -> api = null;
    }

    void enable() {
        BlueMapAPI.onEnable(onEnable);
        BlueMapAPI.onDisable(onDisable);
    }

    void disable() {
        BlueMapAPI.unregisterListener(onEnable);
        BlueMapAPI.unregisterListener(onDisable);
        BlueMapAPI current = api;
        if (current != null) {
            for (BlueMapMap map : current.getMaps()) {
                map.getMarkerSets().remove(SET_ID);
            }
        }
        api = null;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Rebuilds every island marker (main thread: reads module state and crew data). */
    void refresh() {
        BlueMapAPI current = api;
        if (current == null) {
            return;
        }
        String label = module.config().getString("bluemap.label", "Islands");
        boolean circles = module.config().getBoolean("bluemap.circles", true);
        CrewService crews = module.service(CrewService.class);
        for (BlueMapMap map : current.getMaps()) {
            map.getMarkerSets().remove(SET_ID);
        }
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            var bmWorld = current.getWorld(world);
            if (bmWorld.isEmpty()) {
                continue;
            }
            MarkerSet set = new MarkerSet(label, true, module.config().getBoolean("bluemap.hidden-by-default", false));
            for (IslandImpl is : module.all()) {
                if (!is.world.equals(world.getName())) {
                    continue;
                }
                Crew owner = crews == null || is.ownerCrewId == null ? null : crews.crewById(is.ownerCrewId);
                String ownerText = Text.plain(module.ownerLine(is));
                String type = Text.plain(module.typeName(is.type));
                String detail = "<b>" + esc(is.name) + "</b><br>" + esc(type) + "<br>" + esc(ownerText);
                int rgb = switch (is.status) {
                    case NEUTRAL -> 0x55FFFF;
                    case UNCLAIMED -> 0xAAAAAA;
                    case OCCUPIED -> owner != null && owner.color() != null ? owner.color().value() : 0xFF5555;
                };
                set.put("island-" + is.id, new POIMarker.Builder()
                        .label(is.name + " - " + ownerText)
                        .position(is.x + 0.5, is.y + 1.0, is.z + 0.5)
                        .detail(detail)
                        .build());
                if (circles) {
                    set.put("island-area-" + is.id, new ShapeMarker.Builder()
                            .label(is.name)
                            .shape(Shape.createCircle(is.x + 0.5, is.z + 0.5, is.radius, 48), (float) world.getSeaLevel())
                            .lineColor(new Color(rgb, 1f))
                            .fillColor(new Color(rgb, is.status == IslandStatus.OCCUPIED ? 0.25f : 0.12f))
                            .lineWidth(2)
                            .depthTestEnabled(false)
                            .detail(detail)
                            .centerPosition()
                            .build());
                }
            }
            for (BlueMapMap map : bmWorld.get().getMaps()) {
                map.getMarkerSets().put(SET_ID, set);
            }
        }
    }
}
