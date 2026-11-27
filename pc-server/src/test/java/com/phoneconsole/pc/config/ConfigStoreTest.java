package com.phoneconsole.pc.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {

    @Test
    void loadOrCreateTemplateIncludesAutoUnlockWirelessDefaults(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("config.json");
        AppConfig cfg = ConfigStore.loadOrCreate(configPath);
        assertNotNull(cfg.autoUnlock);
        assertFalse(cfg.autoUnlock.wirelessEnabled);
        assertEquals("", cfg.autoUnlock.wirelessEndpoint);
        assertTrue(cfg.autoUnlock.wirelessMdnsAutoDiscover);
    }

    @Test
    void parseValidatesAndNormalizesWirelessEndpoint() throws IOException {
        String json = """
                {
                  "network": { "wsPort": 8500, "httpPort": 8501, "reversePollMs": 3000, "adbExecutable": "adb" },
                  "autoUnlock": {
                    "enabled": true,
                    "wirelessEnabled": true,
                    "wirelessEndpoint": "  10.26.3.135:33007  ",
                    "wirelessMdnsAutoDiscover": true
                  },
                  "pages": [
                    {
                      "id": "default_page",
                      "title": "默认",
                      "grid": { "columns": 4 },
                      "buttons": []
                    }
                  ]
                }
                """;
        AppConfig parsed = ConfigStore.parse(json);
        assertTrue(parsed.autoUnlock.wirelessEnabled);
        assertEquals("10.26.3.135:33007", parsed.autoUnlock.wirelessEndpoint);
        assertTrue(parsed.autoUnlock.wirelessMdnsAutoDiscover);
    }

    @Test
    void parseRejectsMalformedWirelessEndpoint() {
        String[] invalidEndpoints = {
                "10.26.3.135",
                "10.26.3.135:0",
                "10.26.3.135:70000",
                ":33007",
                "bad host:33007"
        };
        for (String endpoint : invalidEndpoints) {
            String json = """
                    {
                      "autoUnlock": {
                        "wirelessEnabled": true,
                        "wirelessEndpoint": "%s"
                      },
                      "pages": [
                        { "id": "default_page", "grid": { "columns": 4 }, "buttons": [] }
                      ]
                    }
                    """.formatted(endpoint);
            IOException ex = assertThrows(IOException.class, () -> ConfigStore.parse(json),
                    "应当拒绝非法无线地址: " + endpoint);
            assertTrue(ex.getMessage().contains("wirelessEndpoint"), ex.getMessage());
        }
    }

    @Test
    void isValidHostPortChecksEdgeCases() {
        assertTrue(ConfigStore.isValidHostPort("10.26.3.135:33007"));
        assertTrue(ConfigStore.isValidHostPort("127.0.0.1:5555"));
        assertTrue(ConfigStore.isValidHostPort("redmi-k50.local:37000"));

        assertFalse(ConfigStore.isValidHostPort(null));
        assertFalse(ConfigStore.isValidHostPort(""));
        assertFalse(ConfigStore.isValidHostPort("10.26.3.135"));
        assertFalse(ConfigStore.isValidHostPort("10.26.3.135:"));
        assertFalse(ConfigStore.isValidHostPort("10.26.3.135:-1"));
        assertFalse(ConfigStore.isValidHostPort("10.26.3.135:65536"));
        assertFalse(ConfigStore.isValidHostPort("10.26.3.135 33007:33007"));
    }
}

