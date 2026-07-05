#pragma once

#include <jni.h>

namespace gnu {

// Native ImGui overlay. Renders inside the glXSwapBuffers hook on the render
// thread; input arrives from a libevdev thread (INSERT toggle). Module data is
// pulled from Java via JniBridge cached-id accessors.
namespace ImGuiGui {

bool init_if_needed();

// Feed the mouse position (window-relative, matching the GL viewport) before
// begin_frame. The glXSwapBuffers hook sources this from XQueryPointer; evdev
// only provides button state.
void set_mouse_position(int x, int y);

void begin_frame(int width, int height, float dt);
void draw();
void draw_hud_overlay(JNIEnv* env);
void end_frame();
void shutdown_context();

void start_input();
bool menu_open();
void toggle_menu();
void set_menu_open(bool open);
bool menu_visible();
bool is_left_down();
bool is_shift_down();

/** Physical mouse REL_X accumulated since last consume (evdev, selected device). */
int consume_mouse_delta_x();
int consume_mouse_delta_y();
/** Milliseconds since last physical REL_X/REL_Y event, or a large value if none yet. */
long long last_mouse_move_age_ms();

} // namespace ImGuiGui

} // namespace gnu
