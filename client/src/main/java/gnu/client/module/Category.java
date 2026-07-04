package gnu.client.module;

// Ordinals are the contract with the native ImGui tab bar
// (0 Combat, 1 Player, 2 Visuals, 3 Misc, 4 Settings, 5 Scripts).
// NOTE: 5 Scripts is NOT yet rendered by the native tab bar — scripts are
// functional (tick/settings) but GUI-invisible until the native imgui_gui.cpp
// tab-bar edit (labels[5], /6.0f, loop<6, draw_tab_icon case 5) lands.
public enum Category {
    COMBAT,
    PLAYER,
    VISUALS,
    MISC,
    SETTINGS,
    SCRIPTS
}
