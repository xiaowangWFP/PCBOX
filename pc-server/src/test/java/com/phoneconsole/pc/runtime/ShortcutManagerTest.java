package com.phoneconsole.pc.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortcutManagerTest {

    @Test
    void normalizeHotkeyReturnsCanonicalModifierOrder() {
        assertEquals("Ctrl+Alt+P", ShortcutManager.normalizeHotkey("alt+ctrl+p"));
        assertEquals("Ctrl+Shift+K", ShortcutManager.normalizeHotkey("  shift + control + k "));
        assertEquals("Ctrl+Alt+Shift+M", ShortcutManager.normalizeHotkey("shift+alt+ctrl+m"));
        assertEquals("F10", ShortcutManager.normalizeHotkey("f10"));
        assertEquals("", ShortcutManager.normalizeHotkey("   "));
        assertEquals("", ShortcutManager.normalizeHotkey(null));
    }

    @Test
    void isValidHotkeyEnforcesSafeCombinations() {
        assertTrue(ShortcutManager.isValidHotkey("Ctrl+Alt+P"));
        assertTrue(ShortcutManager.isValidHotkey("Ctrl+Shift+K"));
        assertTrue(ShortcutManager.isValidHotkey("Alt+Shift+F9"));
        assertTrue(ShortcutManager.isValidHotkey("Ctrl+Alt+Shift+M"));
        assertTrue(ShortcutManager.isValidHotkey("F9"));
        assertTrue(ShortcutManager.isValidHotkey("F12"));

        assertFalse(ShortcutManager.isValidHotkey(""));
        assertFalse(ShortcutManager.isValidHotkey("P"));
        assertFalse(ShortcutManager.isValidHotkey("Ctrl+P"));
        assertFalse(ShortcutManager.isValidHotkey("Alt+P"));
        assertFalse(ShortcutManager.isValidHotkey("Ctrl+Alt+"));
        assertFalse(ShortcutManager.isValidHotkey("F1"));
    }

    @Test
    void bindAndUnbindInvokeScriptAndUpdateCachedStatus(@TempDir Path tempDir) throws IOException {
        Path fakeScript = Files.writeString(tempDir.resolve("bind-shortcut.ps1"), "# mock");
        List<List<String>> invocations = new ArrayList<>();
        ShortcutManager manager = new ShortcutManager(fakeScript, cmd -> {
            invocations.add(new ArrayList<>(cmd));
            if (cmd.contains("unbind")) {
                return "{\"ok\":true,\"bound\":false,\"hotkey\":\"\",\"desktopShortcut\":false,\"autoStart\":false}";
            }
            return "{\"ok\":true,\"bound\":true,\"hotkey\":\"Ctrl+Alt+P\",\"desktopShortcut\":true,\"autoStart\":true}";
        });

        Map<String, Object> bad = manager.bind("Ctrl+P", false, false);
        assertEquals(Boolean.FALSE, bad.get("ok"));
        assertTrue(invocations.isEmpty());

        Map<String, Object> bound = manager.bind("alt+ctrl+p", true, true);
        assertEquals(Boolean.TRUE, bound.get("ok"));
        assertEquals(Boolean.TRUE, bound.get("bound"));
        assertEquals("Ctrl+Alt+P", bound.get("hotkey"));
        assertEquals(Boolean.TRUE, manager.cachedStatus().get("autoStart"));
        assertEquals(1, invocations.size());
        List<String> bindCmd = invocations.get(0);
        assertTrue(bindCmd.contains("bind"));
        assertTrue(bindCmd.contains("Ctrl+Alt+P"));
        assertTrue(bindCmd.contains("-Desktop"));
        assertTrue(bindCmd.contains("enable"));

        Map<String, Object> unbound = manager.unbind(true, true);
        assertEquals(Boolean.TRUE, unbound.get("ok"));
        assertEquals(Boolean.FALSE, unbound.get("bound"));
        assertEquals(Boolean.FALSE, manager.cachedStatus().get("bound"));
        assertEquals(2, invocations.size());
        List<String> unbindCmd = invocations.get(1);
        assertTrue(unbindCmd.contains("unbind"));
        assertTrue(unbindCmd.contains("-RemoveDesktop"));
        assertTrue(unbindCmd.contains("disable"));
    }
}
