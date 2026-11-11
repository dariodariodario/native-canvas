#include <jni.h>
#include <SDL3/SDL.h>

static void push_events(JNIEnv *env, SDL_Event *events, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        if (SDL_PushEvent(&events[i])) continue;
        jclass error = (*env)->FindClass(env, "java/lang/AssertionError");
        if (error) (*env)->ThrowNew(env, error, SDL_GetError());
        return;
    }
}

JNIEXPORT void JNICALL Java_nativecanvas_CanvasTest_enqueueInput(JNIEnv *env, jclass type) {
    SDL_Event events[] = {
        {.key = {.type = SDL_EVENT_KEY_DOWN, .key = SDLK_A, .scancode = SDL_SCANCODE_A,
                 .mod = SDL_KMOD_LSHIFT, .repeat = true}},
        {.key = {.type = SDL_EVENT_KEY_UP, .key = SDLK_A, .scancode = SDL_SCANCODE_A}},
        {.motion = {.type = SDL_EVENT_MOUSE_MOTION, .x = 31.5f, .y = 12, .xrel = -2}},
        {.button = {.type = SDL_EVENT_MOUSE_BUTTON_DOWN, .button = SDL_BUTTON_LEFT,
                    .clicks = 2, .x = 31.5f, .y = 12}},
        {.button = {.type = SDL_EVENT_MOUSE_BUTTON_UP, .button = SDL_BUTTON_LEFT}},
        {.wheel = {.type = SDL_EVENT_MOUSE_WHEEL, .mouse_x = 31.5f, .y = -2,
                   .direction = SDL_MOUSEWHEEL_FLIPPED}},
        {.quit = {.type = SDL_EVENT_QUIT}}
    };
    push_events(env, events, sizeof(events) / sizeof(events[0]));
}

JNIEXPORT void JNICALL Java_nativecanvas_ui_UiTest_enqueueInput(JNIEnv *env, jclass type) {
    SDL_Event events[] = {
        {.motion = {.type = SDL_EVENT_MOUSE_MOTION, .x = 12, .y = 12}},
        {.button = {.type = SDL_EVENT_MOUSE_BUTTON_DOWN, .button = SDL_BUTTON_RIGHT, .x = 12, .y = 12}},
        {.button = {.type = SDL_EVENT_MOUSE_BUTTON_UP, .button = SDL_BUTTON_RIGHT, .x = 12, .y = 12}},
        {.button = {.type = SDL_EVENT_MOUSE_BUTTON_DOWN, .button = SDL_BUTTON_LEFT, .x = 12, .y = 12}},
        {.button = {.type = SDL_EVENT_MOUSE_BUTTON_UP, .button = SDL_BUTTON_LEFT, .x = 12, .y = 12}},
        {.key = {.type = SDL_EVENT_KEY_DOWN, .key = SDLK_TAB}},
        {.key = {.type = SDL_EVENT_KEY_DOWN, .key = SDLK_SPACE, .repeat = true}},
        {.key = {.type = SDL_EVENT_KEY_DOWN, .key = SDLK_RETURN}},
        {.key = {.type = SDL_EVENT_KEY_UP, .key = SDLK_RETURN}},
        {.wheel = {.type = SDL_EVENT_MOUSE_WHEEL, .mouse_x = 12, .mouse_y = 12, .y = -1}},
        {.quit = {.type = SDL_EVENT_QUIT}}
    };
    push_events(env, events, sizeof(events) / sizeof(events[0]));
}

JNIEXPORT void JNICALL Java_nativecanvas_ui_TextFieldTest_enqueueText(JNIEnv *env, jclass type) {
    SDL_Event events[] = {
        {.text = {.type = SDL_EVENT_TEXT_INPUT, .text = "\xc3\xa9\xf0\x9f\x98\x80"}},
        {.key = {.type = SDL_EVENT_KEY_DOWN, .key = SDLK_BACKSPACE}},
        {.key = {.type = SDL_EVENT_KEY_DOWN, .key = SDLK_TAB}}
    };
    push_events(env, events, sizeof(events) / sizeof(events[0]));
}

JNIEXPORT void JNICALL Java_nativecanvas_CanvasTest_exposeDuringResize(JNIEnv *env, jclass type) {
    int count;
    SDL_Window **windows = SDL_GetWindows(&count);
    if (!windows || count != 1) {
        SDL_free(windows);
        jclass error = (*env)->FindClass(env, "java/lang/AssertionError");
        if (error) (*env)->ThrowNew(env, error, "Expected one SDL window");
        return;
    }
    SDL_Event event = {.window = {.type = SDL_EVENT_WINDOW_EXPOSED,
                                 .windowID = SDL_GetWindowID(windows[0]), .data1 = 1}};
    SDL_free(windows);
    push_events(env, &event, 1);
}
