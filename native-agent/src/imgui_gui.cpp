#include "imgui_gui.h"
#include "jni_bridge.h"
#include "jvmti_context.h"
#include "log.h"

#include <imgui.h>
#include <imgui_impl_opengl3.h>

#include <libevdev/libevdev.h>

#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <linux/input.h>

#include <algorithm>
#include <atomic>
#include <cfloat>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <filesystem>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

// Correction 3: INSERT toggle via libevdev evdev keycode, not LWJGL.
static_assert(KEY_INSERT == 110, "evdev KEY_INSERT must be 110");

namespace gnu {
namespace ImGuiGui {

namespace {

namespace fs = std::filesystem;

// ===================== Timewarp palette (exact spec) =====================
const ImVec4 OUTER_BG    = ImVec4(0.098f, 0.098f, 0.141f, 1.0f); // #19191f
const ImVec4 CARD_BG     = ImVec4(0.145f, 0.145f, 0.196f, 1.0f); // #252532
const ImVec4 SELECTED_BG = ImVec4(0.176f, 0.176f, 0.235f, 1.0f); // #2d2d3c
const ImVec4 HOVER_BG    = ImVec4(0.212f, 0.212f, 0.278f, 1.0f);
const ImVec4 TAB_BAR     = ImVec4(0.082f, 0.082f, 0.129f, 1.0f); // #151521
const ImVec4 BORDER_COL  = ImVec4(0.220f, 0.220f, 0.290f, 1.0f);
const ImVec4 TEXT_WHITE  = ImVec4(1.0f, 1.0f, 1.0f, 1.0f);
const ImVec4 TEXT_GRAY   = ImVec4(0.600f, 0.600f, 0.650f, 1.0f); // #999aa6

struct Accent {
    const char* name;
    ImVec4 c;
};
const Accent kAccents[] = {
    {"Timewarp", ImVec4(0.698f, 0.408f, 0.510f, 1.0f)}, // #b2687f
    {"Purple",   ImVec4(0.486f, 0.404f, 0.937f, 1.0f)},
    {"Ocean",    ImVec4(0.227f, 0.741f, 0.847f, 1.0f)},
    {"Forest",   ImVec4(0.298f, 0.808f, 0.396f, 1.0f)},
    {"Sunset",   ImVec4(0.961f, 0.580f, 0.196f, 1.0f)},
};
constexpr int kAccentCount = 5;
int g_accent = 0;

ImVec4 accent() { return kAccents[g_accent].c; }
ImVec4 accent_dim() {
    ImVec4 c = accent();
    return ImVec4(c.x * 0.71f, c.y * 0.71f, c.z * 0.71f, 1.0f);
}
ImU32 U(const ImVec4& c) { return ImGui::ColorConvertFloat4ToU32(c); }
float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

static ImU32 with_alpha(ImU32 col, float a) {
    a = clampf(a, 0.f, 1.f);
    return (col & 0x00FFFFFFu)
           | (static_cast<ImU32>(static_cast<int>(((col >> 24) & 0xFF) * a)) << 24);
}

static ImU32 Ua(const ImVec4& c, float a) {
    ImVec4 v = c;
    v.w *= clampf(a, 0.f, 1.f);
    return U(v);
}

static float smoothstep(float t) {
    t = clampf(t, 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}
static float lerp_f(float a, float b, float t) {
    return a + (b - a) * t;
}
static ImVec4 lerp_col(ImVec4 a, ImVec4 b, float t) {
    return {lerp_f(a.x, b.x, t), lerp_f(a.y, b.y, t), lerp_f(a.z, b.z, t), lerp_f(a.w, b.w, t)};
}

// ---- Fonts ----
ImFont* g_font_regular = nullptr;  // descriptions, values
ImFont* g_font_semibold = nullptr; // module names, labels, tabs
ImFont* g_font_bold = nullptr;     // headers, title, selected

constexpr float SZ_TITLE = 16.0f;
constexpr float SZ_NAME = 14.0f;
constexpr float SZ_DESC = 12.0f;
constexpr float SZ_HEADER = 15.0f;
constexpr float SZ_LABEL = 14.0f;
constexpr float SZ_VALUE = 13.0f;
constexpr float SZ_TAB = 13.0f;

float text_w(ImFont* f, float sz, const char* s) {
    return f->CalcTextSizeA(sz, FLT_MAX, 0.0f, s).x;
}

std::string font_dir() {
    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(&font_dir), &info) && info.dli_fname) {
        fs::path so(info.dli_fname);
        // gnu-agent.so lives in install/lib -> fonts in install/fonts
        return (so.parent_path().parent_path() / "fonts").string();
    }
    return "fonts";
}

void apply_style() {
    ImGuiStyle& s = ImGui::GetStyle();
    s.WindowRounding = 8.0f;
    s.ChildRounding = 6.0f;
    s.FrameRounding = 4.0f;
    s.GrabRounding = 4.0f;
    s.PopupRounding = 6.0f;
    s.WindowBorderSize = 0.0f;
    s.ChildBorderSize = 0.0f;
    s.FrameBorderSize = 0.0f;
    s.PopupBorderSize = 1.0f;
    s.ItemSpacing = ImVec2(8, 6);
    s.WindowPadding = ImVec2(0, 0);
    s.FramePadding = ImVec2(10, 5);
    s.ScrollbarSize = 8.0f;

    ImVec4* c = s.Colors;
    c[ImGuiCol_WindowBg] = OUTER_BG;
    c[ImGuiCol_ChildBg] = ImVec4(0, 0, 0, 0);
    c[ImGuiCol_PopupBg] = CARD_BG;
    c[ImGuiCol_Border] = BORDER_COL;
    c[ImGuiCol_FrameBg] = SELECTED_BG;
    c[ImGuiCol_FrameBgHovered] = HOVER_BG;
    c[ImGuiCol_FrameBgActive] = SELECTED_BG;
    c[ImGuiCol_Text] = TEXT_WHITE;
    c[ImGuiCol_TextDisabled] = TEXT_GRAY;
    c[ImGuiCol_SliderGrab] = accent();
    c[ImGuiCol_SliderGrabActive] = accent_dim();
    c[ImGuiCol_CheckMark] = accent();
    c[ImGuiCol_Button] = SELECTED_BG;
    c[ImGuiCol_ButtonHovered] = HOVER_BG;
    c[ImGuiCol_ButtonActive] = accent_dim();
    c[ImGuiCol_Header] = SELECTED_BG;
    c[ImGuiCol_HeaderHovered] = HOVER_BG;
    c[ImGuiCol_HeaderActive] = accent_dim();
    c[ImGuiCol_ScrollbarBg] = TAB_BAR;
    c[ImGuiCol_ScrollbarGrab] = accent_dim();
    c[ImGuiCol_ScrollbarGrabHovered] = accent();
    c[ImGuiCol_ScrollbarGrabActive] = accent();
}

// ===================== Module metadata cache =====================
struct SettingMeta {
    std::string name;
    int type = 0; // 0 bool, 1 slider, 2 mode
    float min = 0.0f, max = 1.0f;
    std::vector<std::string> mode_names;
};
struct ModuleMeta {
    std::string name, desc;
    int category = 0;
    std::vector<SettingMeta> settings;
};

std::vector<ModuleMeta> g_meta;
bool g_meta_built = false;
int g_tab = 0;       // 0..4 (Combat/Player/Visuals/Misc/Settings)
int g_selected = -1; // index into g_meta

bool g_context_ready = false;

// ---- libevdev input (shared state) ----
std::atomic<bool> g_menu_open{false};
std::atomic<bool> g_input_started{false};

std::mutex g_input_mtx;
bool g_left_down = false;
bool g_right_down = false;
bool g_shift_down = false;

// Mouse position comes from XQueryPointer (window-relative, matches the GL
// viewport) fed by the glXSwapBuffers hook — NOT from evdev deltas, which live
// in a different coordinate space under XWayland/Hyprland and are badly offset.
std::atomic<int> g_mouse_px{0};
std::atomic<int> g_mouse_py{0};

std::atomic<int> g_rel_dx{0};
std::atomic<int> g_rel_dy{0};
std::atomic<long long> g_last_rel_ms{0};

long long steady_now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// ===================== Animation state =====================
constexpr float MENU_DUR   = 0.18f;
constexpr float TAB_DUR    = 0.14f;
constexpr float CARD_DUR   = 0.13f;
constexpr float HOVER_DUR  = 0.10f;
constexpr float TOGGLE_DUR = 0.12f;
constexpr float BASE_ALPHA = 1.0f;

static float g_menu_anim = 0.0f;
static double g_menu_anim_t = 0.0;
static bool g_menu_open_last = false;

static int g_tab_prev = 0;
static float g_tab_anim = 1.0f;
static double g_tab_anim_t = 0.0;

static int g_sel_prev = -1;
static float g_card_anim = 1.0f;
static double g_card_anim_t = 0.0;

static int g_hovered_row = -1;
static float g_hover_anim = 0.0f;
static double g_hover_anim_t = 0.0;

static std::unordered_map<int, float> g_toggle_anim;
static float g_tab_indicator_x = 0.0f;
static bool g_tab_indicator_init = false;

static void update_toggle_anim(JNIEnv* env, JniBridge& b, int mod) {
    const bool en = b.gui_module_enabled(env, mod);
    const float target = en ? 1.f : 0.f;
    float& t = g_toggle_anim[mod];
    const float dt = ImGui::GetIO().DeltaTime;
    t += (target - t) * (dt / TOGGLE_DUR) * 8.f;
    t = clampf(t, 0.f, 1.f);
}

static void update_animations() {
    const bool open = g_menu_open.load(std::memory_order_acquire);
    if (open != g_menu_open_last) {
        g_menu_anim_t = ImGui::GetTime();
        g_menu_open_last = open;
    }
    const float melapsed = static_cast<float>(ImGui::GetTime() - g_menu_anim_t);
    float ms = smoothstep(clampf(melapsed / MENU_DUR, 0.f, 1.f));
    if (!open)
        ms = 1.f - ms;
    g_menu_anim = ms;

    if (g_tab != g_tab_prev) {
        g_tab_anim = 0.f;
        g_tab_anim_t = ImGui::GetTime();
        g_selected = -1;
        g_tab_prev = g_tab;
    }
    const float telapsed = static_cast<float>(ImGui::GetTime() - g_tab_anim_t);
    g_tab_anim = smoothstep(clampf(telapsed / TAB_DUR, 0.f, 1.f));

    if (g_selected != g_sel_prev) {
        g_card_anim = 0.f;
        g_card_anim_t = ImGui::GetTime();
        g_sel_prev = g_selected;
    }
    const float celapsed = static_cast<float>(ImGui::GetTime() - g_card_anim_t);
    g_card_anim = smoothstep(clampf(celapsed / CARD_DUR, 0.f, 1.f));
}

static void update_hover_anim(int new_hovered) {
    if (new_hovered != g_hovered_row) {
        g_hovered_row = new_hovered;
        g_hover_anim_t = ImGui::GetTime();
    }
    const float htarget = (g_hovered_row >= 0) ? 1.f : 0.f;
    const float helapsed = static_cast<float>(ImGui::GetTime() - g_hover_anim_t);
    g_hover_anim = clampf(g_hover_anim + (htarget - g_hover_anim) * (helapsed / HOVER_DUR) * 8.f, 0.f,
                          1.f);
}

void build_meta(JNIEnv* env) {
    g_meta.clear();
    JniBridge& b = JniBridge::instance();
    int n = b.gui_module_count(env);
    for (int i = 0; i < n; ++i) {
        ModuleMeta m;
        m.name = b.gui_module_name(env, i);
        m.desc = b.gui_module_desc(env, i);
        m.category = b.gui_module_category(env, i);
        int sc = b.gui_setting_count(env, i);
        for (int s = 0; s < sc; ++s) {
            SettingMeta sm;
            sm.name = b.gui_setting_name(env, i, s);
            sm.type = b.gui_setting_type(env, i, s);
            if (sm.type == 1) {
                sm.min = b.gui_setting_min(env, i, s);
                sm.max = b.gui_setting_max(env, i, s);
            } else if (sm.type == 2) {
                int mc = b.gui_setting_mode_count(env, i, s);
                for (int mi = 0; mi < mc; ++mi)
                    sm.mode_names.push_back(b.gui_setting_mode_name(env, i, s, mi));
            }
            m.settings.push_back(std::move(sm));
        }
        g_meta.push_back(std::move(m));
    }
    g_meta_built = true;
    log_line("GUI_ module metadata built count=" + std::to_string(n));
}

// ===================== custom widgets =====================

// Thin accent slider with a rounded cap (Timewarp look, no boxy grab).
bool custom_slider(const char* id, float x, float y, float w, float& val,
                   float mn, float mx) {
    ImDrawList* dl = ImGui::GetWindowDrawList();
    ImGui::SetCursorScreenPos(ImVec2(x, y));
    ImGui::InvisibleButton(id, ImVec2(w, 14.0f));
    bool active = ImGui::IsItemActive();

    float ty = y + 5.0f, th = 4.0f;
    float frac = (mx > mn) ? clampf((val - mn) / (mx - mn), 0.0f, 1.0f) : 0.0f;
    dl->AddRectFilled(ImVec2(x, ty), ImVec2(x + w, ty + th), U(SELECTED_BG), 2.0f);
    dl->AddRectFilled(ImVec2(x, ty), ImVec2(x + frac * w, ty + th), U(accent()), 2.0f);
    dl->AddCircleFilled(ImVec2(x + frac * w, ty + th * 0.5f), 5.0f, U(accent()));

    bool changed = false;
    if (active) {
        float mxp = ImGui::GetIO().MousePos.x;
        float nf = clampf((mxp - x) / w, 0.0f, 1.0f);
        float nv = mn + nf * (mx - mn);
        if (nv != val) { val = nv; changed = true; }
    }
    return changed;
}

// Small rounded square toggle. on_t in [0,1] lerps off->accent. Returns true if clicked.
bool square_toggle(const char* id, float x, float y, float sz, float on_t) {
    ImDrawList* dl = ImGui::GetWindowDrawList();
    ImGui::SetCursorScreenPos(ImVec2(x, y));
    ImGui::InvisibleButton(id, ImVec2(sz, sz));
    bool clicked = ImGui::IsItemClicked(ImGuiMouseButton_Left);
    const ImVec4 off(0.30f, 0.30f, 0.36f, 1.0f);
    const ImVec4 col = lerp_col(off, accent(), on_t);
    dl->AddRectFilled(ImVec2(x, y), ImVec2(x + sz, y + sz), U(col), 3.0f);
    if (on_t > 0.05f) {
        ImVec4 glow = accent();
        glow.w = on_t * 0.45f;
        dl->AddRect(ImVec2(x - 1.f, y - 1.f), ImVec2(x + sz + 1.f, y + sz + 1.f), U(glow), 3.5f,
                    0, 1.2f);
    }
    return clicked;
}

// Height of a settings card for the given range (for the bg rect).
float card_height(const ModuleMeta& m, int from, int to, bool enable_row) {
    float h = 14.0f;          // top pad
    h += 24.0f;               // header
    if (enable_row) h += 30.0f;
    for (int s = from; s < to; ++s) {
        switch (m.settings[s].type) {
            case 0: h += 26.0f; break;
            case 1: h += 42.0f; break;
            case 2: h += 50.0f; break;
        }
    }
    h += 14.0f;               // bottom pad
    return h;
}

void render_card(JNIEnv* env, JniBridge& b, int mod, ImVec2 pos, float w,
                 const char* header, int from, int to, bool enable_row) {
    ImDrawList* dl = ImGui::GetWindowDrawList();
    const ModuleMeta& m = g_meta[mod];
    const float pad = 14.0f;
    float h = card_height(m, from, to, enable_row);

    dl->AddRectFilled(pos, ImVec2(pos.x + w, pos.y + h), U(CARD_BG), 6.0f);

    float x = pos.x + pad;
    float cw = w - pad * 2.0f;
    float y = pos.y + pad;

    // Header
    dl->AddText(g_font_bold, SZ_HEADER, ImVec2(x, y), U(TEXT_WHITE), header);
    y += 24.0f;

    // Enable row (module on/off + "?" help)
    if (enable_row) {
        update_toggle_anim(env, b, mod);
        const float on_t = g_toggle_anim[mod];
        ImGui::PushID("enable");
        if (square_toggle("##en", x, y, 14.0f, on_t))
            b.gui_toggle(env, mod);
        dl->AddText(g_font_bold, SZ_LABEL, ImVec2(x + 22.0f, y - 1.0f),
                    U(TEXT_WHITE), "Enable");

        {
            const float kx = x + 22.0f + text_w(g_font_semibold, SZ_LABEL, "Enable") + 10.0f;
            const bool rebinding = b.gui_is_rebind_pending(env, mod);
            std::string key_name = b.gui_module_key_label(env, mod);
            char bind_buf[48];
            if (rebinding)
                std::snprintf(bind_buf, sizeof(bind_buf), "[...]");
            else if (key_name == "NONE")
                std::snprintf(bind_buf, sizeof(bind_buf), "[NONE]");
            else
                std::snprintf(bind_buf, sizeof(bind_buf), "[%s]", key_name.c_str());

            ImGui::SetCursorScreenPos(ImVec2(kx, y - 2.0f));
            ImGui::PushID("keybind");
            if (ImGui::Button(bind_buf, ImVec2(92.0f, 18.0f)))
                b.start_rebind(env, m.name.c_str());
            ImGui::PopID();
        }

        // "?" pill on the right
        float qx = pos.x + w - pad - 22.0f;
        ImGui::SetCursorScreenPos(ImVec2(qx, y - 2.0f));
        ImGui::InvisibleButton("##help", ImVec2(20.0f, 18.0f));
        dl->AddRectFilled(ImVec2(qx, y - 2.0f), ImVec2(qx + 20.0f, y + 16.0f),
                          U(SELECTED_BG), 4.0f);
        float qw = text_w(g_font_semibold, SZ_VALUE, "?");
        dl->AddText(g_font_semibold, SZ_VALUE, ImVec2(qx + 10.0f - qw * 0.5f, y),
                    U(TEXT_GRAY), "?");
        if (ImGui::IsItemHovered())
            ImGui::SetTooltip("%s", m.desc.c_str());
        ImGui::PopID();
        y += 30.0f;
    }

    for (int s = from; s < to; ++s) {
        const SettingMeta& sm = m.settings[s];
        ImGui::PushID(s);
        if (sm.type == 0) {
            bool v = b.gui_setting_bool(env, mod, s);
            if (square_toggle("##b", x, y, 14.0f, v ? 1.f : 0.f))
                b.gui_set_bool(env, mod, s, !v);
            dl->AddText(g_font_semibold, SZ_LABEL, ImVec2(x + 22.0f, y - 1.0f),
                        U(TEXT_WHITE), sm.name.c_str());
            y += 26.0f;
        } else if (sm.type == 1) {
            float v = b.gui_setting_float(env, mod, s);
            dl->AddText(g_font_semibold, SZ_LABEL, ImVec2(x, y), U(TEXT_WHITE),
                        sm.name.c_str());
            char buf[32];
            std::snprintf(buf, sizeof(buf), "%.2f", v);
            float vw = text_w(g_font_regular, SZ_VALUE, buf);
            dl->AddText(g_font_regular, SZ_VALUE, ImVec2(x + cw - vw, y + 1.0f),
                        U(TEXT_GRAY), buf);
            if (custom_slider("##s", x, y + 20.0f, cw, v, sm.min, sm.max))
                b.gui_set_float(env, mod, s, v);
            y += 42.0f;
        } else if (sm.type == 2) {
            dl->AddText(g_font_semibold, SZ_LABEL, ImVec2(x, y), U(TEXT_WHITE),
                        sm.name.c_str());
            int cur = b.gui_setting_mode(env, mod, s);
            std::vector<const char*> items;
            for (const auto& mn : sm.mode_names) items.push_back(mn.c_str());
            ImGui::SetCursorScreenPos(ImVec2(x, y + 19.0f));
            ImGui::SetNextItemWidth(cw);
            if (!items.empty() &&
                ImGui::Combo("##m", &cur, items.data(),
                             static_cast<int>(items.size())))
                b.gui_set_mode(env, mod, s, cur);
            y += 50.0f;
        }
        ImGui::PopID();
    }
}

void draw_tab_icon(ImDrawList* dl, int type, ImVec2 c, ImU32 col) {
    const float r = 8.0f;
    switch (type) {
        case 0: { // Combat — crossed blades
            dl->AddLine(ImVec2(c.x - r, c.y + r), ImVec2(c.x + r, c.y - r), col, 2.0f);
            dl->AddLine(ImVec2(c.x - r, c.y - r), ImVec2(c.x + r, c.y + r), col, 2.0f);
            break;
        }
        case 1: { // Player — head + shoulders
            dl->AddCircleFilled(ImVec2(c.x, c.y - 3.0f), 3.5f, col, 16);
            dl->AddRectFilled(ImVec2(c.x - 5.0f, c.y + 2.0f),
                              ImVec2(c.x + 5.0f, c.y + 7.0f), col, 3.0f);
            break;
        }
        case 2: { // Visuals — eye
            dl->AddCircle(ImVec2(c.x, c.y), r, col, 20, 1.6f);
            dl->AddCircleFilled(ImVec2(c.x, c.y), 3.0f, col, 14);
            break;
        }
        case 3: { // Misc — </>
            dl->AddText(g_font_semibold, SZ_TAB,
                        ImVec2(c.x - text_w(g_font_semibold, SZ_TAB, "</>") * 0.5f,
                               c.y - SZ_TAB * 0.5f),
                        col, "</>");
            break;
        }
        case 4: { // Settings — gear ring
            dl->AddCircle(ImVec2(c.x, c.y), r, col, 20, 1.8f);
            dl->AddCircleFilled(ImVec2(c.x, c.y), 2.5f, col, 12);
            break;
        }
    }
}

void apply_input(ImGuiIO& io) {
    io.AddMousePosEvent(static_cast<float>(g_mouse_px.load(std::memory_order_relaxed)),
                        static_cast<float>(g_mouse_py.load(std::memory_order_relaxed)));
    std::lock_guard<std::mutex> g(g_input_mtx);
    io.AddMouseButtonEvent(0, g_left_down);
    io.AddMouseButtonEvent(1, g_right_down);
}

bool is_virtual_device(const std::string& name) {
    std::string lower = name;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    for (const char* pat : {"virtual", "ydoto", "uinput", "fake"}) {
        if (lower.find(pat) != std::string::npos)
            return true;
    }
    return false;
}

int event_number(const std::string& path) {
    auto pos = path.rfind("event");
    if (pos == std::string::npos)
        return -1;
    return std::atoi(path.c_str() + pos + 5);
}

std::vector<std::string> sorted_event_paths() {
    std::vector<std::string> paths;
    for (const auto& entry : fs::directory_iterator("/dev/input")) {
        auto p = entry.path().string();
        if (p.find("event") != std::string::npos)
            paths.push_back(p);
    }
    std::sort(paths.begin(), paths.end(), [](const std::string& a, const std::string& b) {
        return event_number(a) < event_number(b);
    });
    return paths;
}

struct MouseCandidate {
    std::string path;
    std::string dev_name;
    int score = 0;
};

std::string find_mouse_device(const std::string* prefer_name = nullptr) {
    log_line("GUI_ mouse: scanning /dev/input for physical mouse");
    std::vector<MouseCandidate> candidates;

    for (const auto& path : sorted_event_paths()) {
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK);
        if (fd < 0) {
            log_line("GUI_ mouse: " + path + " open failed errno=" + std::to_string(errno));
            continue;
        }

        libevdev* dev = nullptr;
        if (libevdev_new_from_fd(fd, &dev) < 0) {
            close(fd);
            continue;
        }

        bool has_left = libevdev_has_event_type(dev, EV_KEY) &&
                        libevdev_has_event_code(dev, EV_KEY, BTN_LEFT);
        bool has_rel_x = libevdev_has_event_type(dev, EV_REL) &&
                         libevdev_has_event_code(dev, EV_REL, REL_X);
        bool has_letters = libevdev_has_event_code(dev, EV_KEY, KEY_A);
        const char* name = libevdev_get_name(dev);
        std::string dev_name = name ? name : "(null)";
        bool virt = is_virtual_device(dev_name);
        bool passes_basic = has_left && has_rel_x && !has_letters && !virt;

        int score = 0;
        std::string detail;
        if (passes_basic) {
            if (libevdev_has_event_code(dev, EV_REL, REL_WHEEL))  { score += 3; detail += " +wheel"; }
            if (libevdev_has_event_code(dev, EV_REL, REL_Y))      { score += 1; detail += " +rel_y"; }
            if (libevdev_has_event_code(dev, EV_KEY, BTN_RIGHT))  { score += 1; detail += " +btn_right"; }
            if (libevdev_has_event_code(dev, EV_KEY, BTN_MIDDLE)) { score += 1; detail += " +btn_middle"; }
            if (libevdev_has_event_code(dev, EV_KEY, BTN_EXTRA))  { score += 1; detail += " +btn_extra"; }
            if (libevdev_has_event_code(dev, EV_KEY, BTN_SIDE))   { score += 1; detail += " +btn_side"; }
        }

        std::string reason;
        if (!has_left) reason = "no BTN_LEFT";
        else if (!has_rel_x) reason = "no REL_X";
        else if (has_letters) reason = "has KEY_A (keyboard)";
        else if (virt) reason = "virtual device";
        else reason = "candidate score=" + std::to_string(score) + detail;

        log_line("GUI_ mouse: " + path + " \"" + dev_name + "\" has_left="
                 + (has_left ? "yes" : "no") + " has_rel="
                 + (has_rel_x ? "yes" : "no") + " has_letters="
                 + (has_letters ? "yes" : "no") + " virtual="
                 + (virt ? "yes" : "no") + " -> " + reason);

        libevdev_free(dev);
        close(fd);

        if (passes_basic &&
            (!prefer_name || dev_name == *prefer_name))
            candidates.push_back(MouseCandidate{path, dev_name, score});
    }

    if (candidates.empty()) {
        log_line("GUI_ mouse: no BTN_LEFT+REL_X physical mouse found");
        return {};
    }

    auto best = std::max_element(candidates.begin(), candidates.end(),
        [](const MouseCandidate& a, const MouseCandidate& b) {
            return a.score < b.score;
        });
    log_line("GUI_ mouse: selected " + best->path + " (\"" + best->dev_name
             + "\") score=" + std::to_string(best->score));
    return best->path;
}

// ---- libevdev reader thread ----
void input_thread_main() {
    std::vector<libevdev*> devs;
    std::vector<int> fds;
    std::vector<bool> selected_mouse;
    std::vector<std::string> paths;
    const std::string mouse_path = find_mouse_device();

    DIR* dir = opendir("/dev/input");
    if (dir) {
        struct dirent* ent;
        while ((ent = readdir(dir)) != nullptr) {
            if (std::string(ent->d_name).rfind("event", 0) != 0)
                continue;
            std::string path = std::string("/dev/input/") + ent->d_name;
            int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK);
            if (fd < 0)
                continue;
            libevdev* dev = nullptr;
            if (libevdev_new_from_fd(fd, &dev) < 0) {
                close(fd);
                continue;
            }
            bool useful = libevdev_has_event_type(dev, EV_KEY) ||
                          libevdev_has_event_type(dev, EV_REL);
            if (!useful) {
                libevdev_free(dev);
                close(fd);
                continue;
            }
            devs.push_back(dev);
            fds.push_back(fd);
            selected_mouse.push_back(!mouse_path.empty() && path == mouse_path);
            paths.push_back(path);
        }
        closedir(dir);
    }

    if (devs.empty()) {
        log_line("GUI_ input: no evdev devices opened (permissions?)");
        return;
    }
    log_line("GUI_ input: monitoring " + std::to_string(devs.size()) + " evdev devices");

    std::vector<pollfd> pfds;
    for (int fd : fds)
        pfds.push_back(pollfd{fd, POLLIN, 0});

    while (true) {
        int pr = poll(pfds.data(), pfds.size(), 200);
        if (pr < 0)
            break;
        for (size_t i = 0; i < devs.size(); ++i) {
            if (selected_mouse[i] &&
                (pfds[i].revents & (POLLHUP | POLLERR | POLLNVAL))) {
                log_line("GUI_ mouse: fd dead (revents=" +
                         std::to_string(pfds[i].revents) + "), reconnecting");

                const std::string old_path = paths[i];
                std::string old_name;
                if (const char* nm = libevdev_get_name(devs[i]))
                    old_name = nm;

                libevdev_free(devs[i]);
                close(fds[i]);
                devs[i] = nullptr;
                fds[i] = -1;
                selected_mouse[i] = false;
                paths[i].clear();
                pfds[i].fd = -1;
                pfds[i].revents = 0;

                {
                    std::lock_guard<std::mutex> g(g_input_mtx);
                    g_left_down = false;
                    g_right_down = false;
                }

                std::string new_path;
                constexpr int kReconnectAttempts = 5;
                constexpr useconds_t kReconnectDelayUs = 180000; // ~180ms
                if (!old_name.empty()) {
                    for (int attempt = 1; attempt <= kReconnectAttempts; ++attempt) {
                        new_path = find_mouse_device(&old_name);
                        if (!new_path.empty()) {
                            log_line("GUI_ mouse: \"" + old_name + "\" reappeared at " +
                                     new_path + " (was " + old_path + ", attempt " +
                                     std::to_string(attempt) + ")");
                            break;
                        }
                        if (attempt < kReconnectAttempts)
                            usleep(kReconnectDelayUs);
                    }
                }
                if (new_path.empty()) {
                    new_path = find_mouse_device();
                    if (!new_path.empty() && !old_name.empty()) {
                        int probe_fd = open(new_path.c_str(), O_RDONLY | O_NONBLOCK);
                        std::string fallback_name = "(unknown)";
                        if (probe_fd >= 0) {
                            libevdev* probe_dev = nullptr;
                            if (libevdev_new_from_fd(probe_fd, &probe_dev) >= 0) {
                                if (const char* nm = libevdev_get_name(probe_dev))
                                    fallback_name = nm;
                                libevdev_free(probe_dev);
                            }
                            close(probe_fd);
                        }
                        log_line("GUI_ mouse: original device \"" + old_name +
                                 "\" not found after ~" +
                                 std::to_string((kReconnectAttempts * kReconnectDelayUs) / 1000000) +
                                 "s, falling back to " + new_path + " (\"" + fallback_name + "\")");
                    }
                }
                if (new_path.empty())
                    continue;

                bool already_open = false;
                for (size_t j = 0; j < paths.size(); ++j) {
                    if (j == i || paths[j].empty())
                        continue;
                    if (paths[j] == new_path) {
                        selected_mouse[j] = true;
                        log_line("GUI_ mouse: reconnected at existing slot " +
                                 std::to_string(j) + " " + new_path);
                        already_open = true;
                        break;
                    }
                }
                if (already_open)
                    continue;

                int new_fd = open(new_path.c_str(), O_RDONLY | O_NONBLOCK);
                if (new_fd < 0) {
                    log_line("GUI_ mouse: reopen failed errno=" + std::to_string(errno));
                    continue;
                }
                libevdev* new_dev = nullptr;
                if (libevdev_new_from_fd(new_fd, &new_dev) < 0) {
                    close(new_fd);
                    log_line("GUI_ mouse: libevdev_new_from_fd failed errno=" +
                             std::to_string(errno));
                    continue;
                }

                devs[i] = new_dev;
                fds[i] = new_fd;
                selected_mouse[i] = true;
                paths[i] = new_path;
                pfds[i].fd = new_fd;
                pfds[i].events = POLLIN;
                pfds[i].revents = 0;
                log_line("GUI_ mouse: reconnected " + new_path);
                continue;
            }

            if (!(pfds[i].revents & POLLIN))
                continue;
            input_event ev;
            int rc;
            while ((rc = libevdev_next_event(devs[i], LIBEVDEV_READ_FLAG_NORMAL, &ev)) ==
                   LIBEVDEV_READ_STATUS_SUCCESS) {
                if (ev.type == EV_KEY && ev.code == KEY_INSERT) {
                    if (ev.value == 1) { // press edge
                        bool now = !g_menu_open.load(std::memory_order_acquire);
                        g_menu_open.store(now, std::memory_order_release);
                    }
                } else if (ev.type == EV_KEY && ev.code == KEY_ESC && ev.value == 1) {
                    g_menu_open.store(false, std::memory_order_release);
                } else if (ev.type == EV_KEY && ev.code == BTN_LEFT && selected_mouse[i]) {
                    std::lock_guard<std::mutex> g(g_input_mtx);
                    g_left_down = (ev.value != 0);
                } else if (ev.type == EV_KEY && ev.code == BTN_RIGHT && selected_mouse[i]) {
                    std::lock_guard<std::mutex> g(g_input_mtx);
                    g_right_down = (ev.value != 0);
                } else if (ev.type == EV_KEY && (ev.code == 42 || ev.code == 54)) {
                    std::lock_guard<std::mutex> g(g_input_mtx);
                    g_shift_down = (ev.value != 0);
                } else if (ev.type == EV_REL && selected_mouse[i]) {
                    if (ev.code == REL_X)
                        g_rel_dx.fetch_add(ev.value, std::memory_order_relaxed);
                    else if (ev.code == REL_Y)
                        g_rel_dy.fetch_add(ev.value, std::memory_order_relaxed);
                    if (ev.code == REL_X || ev.code == REL_Y)
                        g_last_rel_ms.store(steady_now_ms(), std::memory_order_relaxed);
                }
                // Window position still comes from XQueryPointer (see set_mouse_position).
            }
            (void)rc;
        }
    }

    for (auto* d : devs)
        libevdev_free(d);
    for (int fd : fds)
        close(fd);
}

} // namespace

bool init_if_needed() {
    if (g_context_ready)
        return true;
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    // Rely on the system cursor: no ImGui software cursor and no backend cursor
    // override (avoids a second cursor drawn over the OS one).
    io.MouseDrawCursor = false;
    io.ConfigFlags |= ImGuiConfigFlags_NoMouseCursorChange;

    std::string fd = font_dir();
    std::string reg = fd + "/Nunito-Regular.ttf";
    std::string semi = fd + "/Nunito-SemiBold.ttf";
    std::string bold = fd + "/Nunito-Bold.ttf";
    bool have_fonts = (access(reg.c_str(), R_OK) == 0) &&
                      (access(semi.c_str(), R_OK) == 0) &&
                      (access(bold.c_str(), R_OK) == 0);
    if (have_fonts) {
        g_font_regular = io.Fonts->AddFontFromFileTTF(reg.c_str(), 13.0f);
        g_font_semibold = io.Fonts->AddFontFromFileTTF(semi.c_str(), 14.0f);
        g_font_bold = io.Fonts->AddFontFromFileTTF(bold.c_str(), 16.0f);
        log_line("GUI_ fonts loaded from " + fd);
    }
    if (!g_font_regular) {
        ImFont* def = io.Fonts->AddFontDefault();
        g_font_regular = g_font_semibold = g_font_bold = def;
        log_line("GUI_ fonts missing at " + fd + " — using default");
    }
    io.FontDefault = g_font_regular;

    apply_style();

    if (!ImGui_ImplOpenGL3_Init("#version 130")) {
        log_line("GUI_ ImGui_ImplOpenGL3_Init failed");
        ImGui::DestroyContext();
        return false;
    }

    // This vendored ImGui is 1.92 (dynamic font atlas). ImGui_ImplOpenGL3_Init
    // sets ImGuiBackendFlags_RendererHasTextures, which switches the atlas to
    // on-demand glyph rasterization — that path crashes on first text render in
    // this GL setup. Force the legacy static atlas: clear the flag and Build()
    // once here. Because the flag is cleared, the per-frame
    // "you don't need to call Build()" user-error (the font-atlas log spam) in
    // ImFontAtlasUpdateNewFrame never fires. (The old spam came from Build()
    // running while that flag was set.)
    io.BackendFlags &= ~ImGuiBackendFlags_RendererHasTextures;
    io.Fonts->Build();

    g_context_ready = true;
    return true;
}

void set_mouse_position(int x, int y) {
    g_mouse_px.store(x, std::memory_order_relaxed);
    g_mouse_py.store(y, std::memory_order_relaxed);
}

void begin_frame(int width, int height, float dt) {
    ImGui_ImplOpenGL3_NewFrame();
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2(static_cast<float>(width), static_cast<float>(height));
    io.DeltaTime = dt > 0.0f ? dt : 1.0f / 60.0f;
    io.MouseDrawCursor = false;
    apply_input(io);
    ImGui::NewFrame();
}

void draw() {
    JNIEnv* env = JvmtiContext::instance().attach_current_thread();
    if (!env)
        return;
    if (!g_meta_built)
        build_meta(env);

    update_animations();

    JniBridge& b = JniBridge::instance();
    ImGuiIO& io = ImGui::GetIO();

    // Clear selection if it belongs to another tab (no auto-pick on tab switch).
    if (g_selected >= 0 && g_selected < static_cast<int>(g_meta.size()) &&
        g_meta[g_selected].category != g_tab) {
        g_selected = -1;
    }

    const float W = 850.0f, H = 520.0f;
    const float ma = g_menu_anim;
    const float scale = lerp_f(0.92f, 1.0f, ma);
    const float aW = W * scale;
    const float aH = H * scale;
    const float sw = io.DisplaySize.x;
    const float sh = io.DisplaySize.y;
    const float cx = sw * 0.5f;
    const float cy = sh * 0.5f;

    ImGui::SetNextWindowSize(ImVec2(aW, aH), ImGuiCond_Always);
    ImGui::SetNextWindowPos(ImVec2(cx - aW * 0.5f, cy - aH * 0.5f), ImGuiCond_Always);
    ImGui::SetNextWindowBgAlpha(ma * BASE_ALPHA);

    ImGuiWindowFlags flags = ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoResize |
                             ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoScrollbar |
                             ImGuiWindowFlags_NoScrollWithMouse |
                             ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_NoSavedSettings;
    ImGui::PushStyleVar(ImGuiStyleVar_Alpha, ma);
    if (!ImGui::Begin("##gnuclient", nullptr, flags)) {
        ImGui::End();
        ImGui::PopStyleVar();
        return;
    }

    ImVec2 p0 = ImGui::GetWindowPos();
    ImDrawList* dl = ImGui::GetWindowDrawList();
    const float TOP_H = 32.0f, BOT_H = 52.0f, LEFT_W = 160.0f;
    const float slide_x = lerp_f(20.f, 0.f, g_tab_anim);
    const float list_alpha = g_tab_anim;
    const float card_offset = lerp_f(18.f, 0.f, g_card_anim);
    const float card_alpha = g_card_anim;

    // ---- Top bar: centered "GNU"+"Client", palette dropdown right ----
    {
        float gw = text_w(g_font_semibold, SZ_TITLE, "GNU");
        float cw = text_w(g_font_bold, SZ_TITLE, "Client");
        float sx = p0.x + (aW - (gw + cw)) * 0.5f;
        float ty = p0.y + (TOP_H - SZ_TITLE) * 0.5f;
        dl->AddText(g_font_semibold, SZ_TITLE, ImVec2(sx, ty), U(accent()), "GNU");
        dl->AddText(g_font_bold, SZ_TITLE, ImVec2(sx + gw, ty), U(accent()), "Client");

        ImGui::SetCursorScreenPos(ImVec2(p0.x + aW - 130.0f, p0.y + 5.0f));
        ImGui::SetNextItemWidth(120.0f);
        const char* names[kAccentCount];
        for (int i = 0; i < kAccentCount; ++i) names[i] = kAccents[i].name;
        int a = g_accent;
        if (ImGui::Combo("##palette", &a, names, kAccentCount)) {
            g_accent = a;
            apply_style();
        }
    }

    // ---- Left panel: scrollable module list ----
    {
        const ImVec2 clip_min(p0.x, p0.y + TOP_H);
        const ImVec2 clip_max(p0.x + LEFT_W, p0.y + aH - BOT_H);
        const float panel_h = aH - TOP_H - BOT_H;

        ImGui::SetCursorScreenPos(clip_min);
        ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0, 0, 0, 0));
        ImGui::PushStyleVar(ImGuiStyleVar_Alpha, list_alpha * ma);
        const ImGuiWindowFlags list_flags = ImGuiWindowFlags_AlwaysVerticalScrollbar;
        int hover_row = -1;
        if (ImGui::BeginChild("##modlist", ImVec2(LEFT_W, panel_h), false, list_flags)) {
            float lx = ImGui::GetWindowPos().x + slide_x;
            float ly = ImGui::GetCursorScreenPos().y;
            const float rowH = 44.0f;
            int visible = 0;
            for (int i = 0; i < static_cast<int>(g_meta.size()); ++i) {
                if (g_meta[i].category != g_tab)
                    continue;
                visible++;

                update_toggle_anim(env, b, i);
                const float en_t = g_toggle_anim[i];

                ImGui::PushID(i);
                ImVec2 rmin(lx, ly), rmax(lx + LEFT_W, ly + rowH);
                ImGui::SetCursorScreenPos(rmin);
                ImGui::InvisibleButton("##row", ImVec2(LEFT_W, rowH));
                bool hovered = ImGui::IsItemHovered();
                if (hovered)
                    hover_row = i;
                if (ImGui::IsItemClicked(ImGuiMouseButton_Left))
                    g_selected = i;
                if (ImGui::IsItemClicked(ImGuiMouseButton_Right))
                    b.gui_toggle(env, i);

                const bool selected = (g_selected == i);
                const float row_glow = (i == g_hovered_row) ? g_hover_anim : 0.f;

                if (en_t > 0.01f) {
                    ImVec4 bar = accent();
                    bar.w = en_t * list_alpha;
                    dl->AddRectFilled(ImVec2(rmin.x + 6.f, rmin.y + 2.f),
                                      ImVec2(rmin.x + 9.f, rmax.y - 2.f), U(bar), 1.5f);
                }

                ImVec4 row_bg = HOVER_BG;
                if (selected)
                    row_bg = SELECTED_BG;
                else if (hovered)
                    row_bg = HOVER_BG;
                row_bg.w *= list_alpha;
                if (hovered && row_glow > 0.01f)
                    row_bg.w *= row_glow;
                dl->AddRectFilled(ImVec2(rmin.x + 6, rmin.y + 2), ImVec2(rmax.x - 6, rmax.y - 2),
                                  U(row_bg), 6.0f);

                ImVec4 ncol = selected ? accent() : TEXT_WHITE;
                ncol.w *= list_alpha;
                ImVec4 dcol = TEXT_GRAY;
                dcol.w *= list_alpha;
                dl->AddText(g_font_semibold, SZ_NAME, ImVec2(lx + 16, ly + 7), U(ncol),
                            g_meta[i].name.c_str());
                dl->AddText(g_font_regular, SZ_DESC, ImVec2(lx + 16, ly + 24), U(dcol),
                            g_meta[i].desc.c_str());
                ImGui::PopID();
                ly += rowH;
            }
            if (visible > 0)
                ImGui::Dummy(ImVec2(LEFT_W - 12.0f, visible * rowH + 6.0f));
        }
        ImGui::EndChild();
        ImGui::PopStyleVar();
        ImGui::PopStyleColor();
        update_hover_anim(hover_row);

        dl->AddLine(clip_min, ImVec2(clip_max.x, clip_min.y), Ua(BORDER_COL, list_alpha), 1.0f);
    }

    // ---- Settings panel: single scrollable card ----
    if (g_selected >= 0 && g_selected < static_cast<int>(g_meta.size())) {
        const ModuleMeta& m = g_meta[g_selected];
        const int total = static_cast<int>(m.settings.size());
        const float panel_x = p0.x + LEFT_W;
        const float panel_y = p0.y + TOP_H;
        const float panel_w = aW - LEFT_W;
        const float panel_h = aH - TOP_H - BOT_H;

        ImGui::SetCursorScreenPos(ImVec2(panel_x, panel_y));
        ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0, 0, 0, 0));
        ImGui::PushStyleVar(ImGuiStyleVar_Alpha, card_alpha * ma);
        const ImGuiWindowFlags settings_flags = ImGuiWindowFlags_AlwaysVerticalScrollbar;
        if (ImGui::BeginChild("##settings_scroll", ImVec2(panel_w, panel_h), false, settings_flags)) {
            const float pad_x = 18.0f + card_offset;
            const float card_w = panel_w - pad_x - 24.0f;
            ImVec2 card_pos(ImGui::GetWindowPos().x + pad_x, ImGui::GetCursorScreenPos().y + 14.0f);
            render_card(env, b, g_selected, card_pos, card_w, m.name.c_str(), 0, total, true);
            ImGui::Dummy(ImVec2(card_w, card_height(m, 0, total, true) + 28.0f));
        }
        ImGui::EndChild();
        ImGui::PopStyleVar();
        ImGui::PopStyleColor();
    }

    // ---- Bottom tab bar ----
    {
        float by = p0.y + aH - BOT_H;
        dl->AddRectFilled(ImVec2(p0.x, by), ImVec2(p0.x + aW, p0.y + aH), U(TAB_BAR));
        const char* labels[5] = {"Combat", "Player", "Visuals", "Misc", "Settings"};
        const float tabW = aW / 5.0f;
        const float target_x = p0.x + g_tab * tabW + tabW * 0.5f - 20.f;
        if (!g_tab_indicator_init) {
            g_tab_indicator_x = target_x;
            g_tab_indicator_init = true;
        }
        g_tab_indicator_x += (target_x - g_tab_indicator_x) * io.DeltaTime * 40.f;
        dl->AddRectFilled(ImVec2(g_tab_indicator_x, p0.y + aH - 2.f),
                          ImVec2(g_tab_indicator_x + 40.f, p0.y + aH), U(accent()));

        for (int t = 0; t < 5; ++t) {
            ImGui::PushID(1000 + t);
            ImVec2 tmin(p0.x + t * tabW, by);
            ImGui::SetCursorScreenPos(tmin);
            ImGui::InvisibleButton("##tab", ImVec2(tabW, BOT_H));
            if (ImGui::IsItemClicked(ImGuiMouseButton_Left))
                g_tab = t;
            bool active = (g_tab == t);
            ImVec4 col = active ? accent() : TEXT_GRAY;
            ImVec2 center(tmin.x + tabW * 0.5f, by + 16.0f);
            draw_tab_icon(dl, t, center, U(col));
            float lw = text_w(g_font_semibold, SZ_TAB, labels[t]);
            dl->AddText(g_font_semibold, SZ_TAB, ImVec2(center.x - lw * 0.5f, by + 30.0f), U(col),
                        labels[t]);
            ImGui::PopID();
        }
    }

    ImGui::End();
    ImGui::PopStyleVar();
}

void draw_hud_overlay(JNIEnv* env) {
    if (!env)
        return;
    JniBridge& b = JniBridge::instance();
    if (!b.hud_should_draw(env))
        return;

    ImGuiIO& io = ImGui::GetIO();
    const float sw = io.DisplaySize.x;
    const float sh = io.DisplaySize.y;
    const float pad = 10.0f;
    const ImVec4 disabled_col = ImVec4(0.92f, 0.38f, 0.38f, 1.0f);

    ImGuiWindowFlags overlay_flags = ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoResize |
                                     ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoScrollbar |
                                     ImGuiWindowFlags_NoScrollWithMouse | ImGuiWindowFlags_NoCollapse |
                                     ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoInputs |
                                     ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoBringToFrontOnFocus;

    ImGui::SetNextWindowPos(ImVec2(0.0f, 0.0f), ImGuiCond_Always);
    ImGui::SetNextWindowSize(io.DisplaySize, ImGuiCond_Always);
    if (!ImGui::Begin("##gnu_hud", nullptr, overlay_flags)) {
        ImGui::End();
        return;
    }
    ImDrawList* dl = ImGui::GetWindowDrawList();

    if (b.hud_show_array(env)) {
        const int count = b.hud_enabled_module_count(env);
        float max_w = text_w(g_font_semibold, SZ_VALUE, "Active");
        for (int i = 0; i < count; ++i) {
            std::string name = b.hud_enabled_module_name(env, i);
            max_w = std::max(max_w, text_w(g_font_semibold, SZ_VALUE, name.c_str()));
        }

        const float row_h = 18.0f;
        const float header_h = 22.0f;
        const float card_w = max_w + 28.0f;
        const float card_h = header_h + (count > 0 ? count * row_h + 6.0f : 8.0f);
        const float card_x = sw - card_w - pad;
        const float card_y = pad;

        ImVec4 card = CARD_BG;
        card.w = 0.88f;
        dl->AddRectFilled(ImVec2(card_x, card_y), ImVec2(card_x + card_w, card_y + card_h), U(card), 8.0f);
        dl->AddRect(ImVec2(card_x, card_y), ImVec2(card_x + card_w, card_y + card_h), Ua(BORDER_COL, 0.9f),
                    8.0f, 0, 1.0f);

        ImVec4 header = accent();
        dl->AddText(g_font_bold, SZ_VALUE, ImVec2(card_x + 12.0f, card_y + 4.0f), U(header), "Active");

        float row_y = card_y + header_h;
        for (int i = 0; i < count; ++i) {
            std::string name = b.hud_enabled_module_name(env, i);
            dl->AddRectFilled(ImVec2(card_x + 10.0f, row_y + 3.0f), ImVec2(card_x + 13.0f, row_y + row_h - 3.0f),
                              U(accent()), 1.5f);
            dl->AddText(g_font_semibold, SZ_VALUE, ImVec2(card_x + 18.0f, row_y + 1.0f), U(TEXT_WHITE),
                        name.c_str());
            row_y += row_h;
        }
    }

    if (b.hud_show_notifications(env)) {
        const int notif_count = b.hud_notification_count(env);
        const float toast_h = 30.0f;
        const float toast_gap = 6.0f;
        float toast_y = sh - pad;

        for (int i = 0; i < notif_count; ++i) {
            const float alpha = b.hud_notification_alpha(env, i);
            if (alpha <= 0.01f)
                continue;

            std::string text = b.hud_notification_text(env, i);
            const bool enabled = b.hud_notification_enabled(env, i);
            const float text_w_px = text_w(g_font_semibold, SZ_VALUE, text.c_str());
            const float toast_w = text_w_px + 28.0f;
            toast_y -= toast_h;
            const float toast_x = sw - toast_w - pad;
            const float slide = lerp_f(24.0f, 0.0f, smoothstep(alpha));

            ImVec4 bg = CARD_BG;
            bg.w = 0.92f * alpha;
            ImVec4 border = enabled ? accent() : disabled_col;
            border.w *= alpha;

            dl->AddRectFilled(ImVec2(toast_x + slide, toast_y), ImVec2(toast_x + slide + toast_w, toast_y + toast_h),
                              U(bg), 7.0f);
            dl->AddRect(ImVec2(toast_x + slide, toast_y),
                        ImVec2(toast_x + slide + toast_w, toast_y + toast_h), U(border), 7.0f, 0, 1.2f);

            ImVec4 text_col = enabled ? TEXT_WHITE : ImVec4(1.0f, 0.88f, 0.88f, 1.0f);
            text_col.w *= alpha;
            dl->AddText(g_font_semibold, SZ_VALUE, ImVec2(toast_x + slide + 12.0f, toast_y + 7.0f), U(text_col),
                        text.c_str());

            toast_y -= toast_gap;
        }
    }

    ImGui::End();
}

void end_frame() {
    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

void shutdown_context() {
    if (ImGuiContext* ctx = ImGui::GetCurrentContext()) {
        ImGui::SetCurrentContext(ctx);
        ImGui::DestroyContext(ctx);
    }
    g_context_ready = false;
}

void start_input() {
    bool expected = false;
    if (!g_input_started.compare_exchange_strong(expected, true))
        return;
    std::thread(input_thread_main).detach();
    log_line("GUI_ input thread started");
}

bool menu_open() {
    return g_menu_open.load(std::memory_order_acquire);
}

bool menu_visible() {
    return menu_open() || g_menu_anim > 0.001f;
}

bool is_left_down() {
    std::lock_guard<std::mutex> g(g_input_mtx);
    return g_left_down;
}

bool is_shift_down() {
    std::lock_guard<std::mutex> g(g_input_mtx);
    return g_shift_down;
}

int consume_mouse_delta_x() {
    return g_rel_dx.exchange(0, std::memory_order_relaxed);
}

int consume_mouse_delta_y() {
    return g_rel_dy.exchange(0, std::memory_order_relaxed);
}

long long last_mouse_move_age_ms() {
    long long last = g_last_rel_ms.load(std::memory_order_relaxed);
    if (last <= 0)
        return 999999LL;
    long long age = steady_now_ms() - last;
    return age < 0 ? 0 : age;
}

} // namespace ImGuiGui
} // namespace gnu
