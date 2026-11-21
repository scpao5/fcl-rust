package io.github.juanluci.starling;

/**
 * Maps FCL's Linux key codes (FCLKeycodes, which are also the codes sdL and the launcher's own
 * on-screen buttons use) to the names the engine knows a key by (winit's KeyCode variants).
 *
 * <p>Only the keys a launcher puts on screen are here: a full keyboard reaches the game as
 * Android's own key events, and this exists for the buttons that are not a keyboard.
 */
public final class InputKeys {
    private InputKeys() {
    }

    /** The engine's name for this key, or null when it has none of its own. */
    public static String name(int code) {
        switch (code) {
            case 17: return "KeyW";
            case 30: return "KeyA";
            case 31: return "KeyS";
            case 32: return "KeyD";
            case 16: return "KeyQ";
            case 18: return "KeyE";
            case 19: return "KeyR";
            case 20: return "KeyT";
            case 33: return "KeyF";
            case 21: return "KeyY";
            case 22: return "KeyU";
            case 23: return "KeyI";
            case 24: return "KeyO";
            case 25: return "KeyP";
            case 38: return "KeyL";
            case 46: return "KeyC";
            case 2: return "Digit1";
            case 3: return "Digit2";
            case 4: return "Digit3";
            case 5: return "Digit4";
            case 6: return "Digit5";
            case 7: return "Digit6";
            case 8: return "Digit7";
            case 9: return "Digit8";
            case 10: return "Digit9";
            case 11: return "Digit0";
            case 57: return "Space";
            case 42: return "ShiftLeft";
            case 54: return "ShiftRight";
            case 29: return "ControlLeft";
            case 56: return "AltLeft";
            case 28: return "Enter";
            case 1: return "Escape";
            case 15: return "Tab";
            case 14: return "Backspace";
            case 59: return "F1";
            case 60: return "F2";
            case 61: return "F3";
            case 71: return "Numpad7";
            case 72: return "Numpad8";
            case 73: return "Numpad9";
            case 74: return "NumpadSubtract";
            case 75: return "Numpad4";
            case 76: return "Numpad5";
            case 77: return "Numpad6";
            case 78: return "NumpadAdd";
            case 79: return "Numpad1";
            case 80: return "Numpad2";
            case 81: return "Numpad3";
            case 82: return "Numpad0";
            case 83: return "NumpadDecimal";
            case 69: return "NumLock";
            case 70: return "ScrollLock";
            case 96: return "NumpadEnter";
            case 98: return "NumpadDivide";
            case 119: return "Pause";
            case 12: return "Minus";
            case 13: return "Equal";
            case 26: return "BracketLeft";
            case 27: return "BracketRight";
            case 39: return "Semicolon";
            case 40: return "Quote";
            case 41: return "Backquote";
            case 43: return "Backslash";
            case 51: return "Comma";
            case 52: return "Period";
            case 53: return "Slash";
            case 58: return "CapsLock";
            case 64: return "F6";
            case 65: return "F7";
            case 66: return "F8";
            case 67: return "F9";
            case 68: return "F10";
            case 87: return "F11";
            case 88: return "F12";
            case 102: return "Home";
            case 107: return "End";
            case 110: return "Insert";
            case 111: return "Delete";
            case 34: return "KeyG";
            case 35: return "KeyH";
            case 36: return "KeyJ";
            case 37: return "KeyK";
            case 44: return "KeyZ";
            case 45: return "KeyX";
            case 47: return "KeyV";
            case 48: return "KeyB";
            case 49: return "KeyN";
            case 50: return "KeyM";
            case 97: return "ControlRight";
            case 100: return "AltRight";
            case 62: return "F4";
            case 63: return "F5";
            case 103: return "ArrowUp";
            case 108: return "ArrowDown";
            case 105: return "ArrowLeft";
            case 106: return "ArrowRight";
            case 104: return "PageUp";
            case 109: return "PageDown";
            default: return null;
        }
    }
}
