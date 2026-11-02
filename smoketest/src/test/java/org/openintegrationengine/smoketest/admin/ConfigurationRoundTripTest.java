// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Tony Germano

package org.openintegrationengine.smoketest.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.smoketest.SharedServer;

import com.mirth.connect.client.core.Client;
import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.ServerSettings;
import com.mirth.connect.model.alert.AlertActionGroup;
import com.mirth.connect.model.alert.AlertModel;
import com.mirth.connect.model.alert.DefaultTrigger;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.util.ConfigurationProperty;

/**
 * Saves each kind of server configuration the administrator edits, reads it back, and removes
 * or restores it. Every test leaves the server as it found it.
 */
@DisplayName("Configuration round trips")
class ConfigurationRoundTripTest {

    /**
     * The update replaces the server's whole set of groups and deletes any group it leaves out,
     * so every request sends the existing groups along with the test's own.
     */
    @Test
    @DisplayName("saves, reads and removes a channel group")
    void channelGroup() throws Exception {
        Channel channel = CoverageChannel.create("group member");
        assertTrue(admin().createChannel(channel), "createChannel");
        ChannelGroup group = new ChannelGroup("Smoke group " + suffix(), "created by the smoke tests");
        group.setChannels(new ArrayList<>(List.of(new Channel(channel.getId()))));
        try {
            Set<ChannelGroup> groups = new HashSet<>(admin().getAllChannelGroups());
            groups.add(group);
            assertTrue(admin().updateChannelGroups(groups, new HashSet<>(), false), "updateChannelGroups");

            ChannelGroup saved = findGroup(group.getId());
            assertNotNull(saved, "group " + group.getId());
            assertEquals(group.getName(), saved.getName());
            assertEquals(channel.getId(), saved.getChannels().get(0).getId(), "member channel");
        } finally {
            try {
                Set<ChannelGroup> others = new HashSet<>();
                for (ChannelGroup existing : admin().getAllChannelGroups()) {
                    if (!existing.getId().equals(group.getId())) {
                        others.add(existing);
                    }
                }
                admin().updateChannelGroups(others, ids(group.getId()), false);
            } finally {
                admin().removeChannel(channel.getId());
            }
        }

        assertNull(findGroup(group.getId()), "group after removal");
    }

    /** Lists every group, because a lookup by id logs an error for an id that doesn't exist. */
    private static ChannelGroup findGroup(String id) throws Exception {
        return admin().getAllChannelGroups().stream().filter(g -> g.getId().equals(id)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("saves, reads and removes a code template library")
    void codeTemplateLibrary() throws Exception {
        List<CodeTemplateLibrary> original = admin().getCodeTemplateLibraries(null, false);

        CodeTemplate template = CodeTemplate.getDefaultCodeTemplate("smokeTemplate" + suffix());
        CodeTemplateLibrary library = new CodeTemplateLibrary();
        library.setName("Smoke library " + suffix());
        library.setCodeTemplates(new ArrayList<>(List.of(new CodeTemplate(template.getId()))));

        List<CodeTemplateLibrary> withNew = new ArrayList<>(original);
        withNew.add(library);
        try {
            assertTrue(admin().updateCodeTemplate(template, false), "updateCodeTemplate");
            assertTrue(admin().updateCodeTemplateLibraries(withNew, false), "updateCodeTemplateLibraries");

            CodeTemplateLibrary saved = admin().getCodeTemplateLibrary(library.getId(), true);
            assertNotNull(saved, "library " + library.getId());
            assertEquals(library.getName(), saved.getName());
            assertEquals(1, saved.getCodeTemplates().size(), "templates in the library");
            assertEquals(template.getCode(), saved.getCodeTemplates().get(0).getCode(), "template code");
        } finally {
            try {
                admin().updateCodeTemplateLibraries(original, true);
            } finally {
                admin().removeCodeTemplate(template.getId());
            }
        }

        assertTrue(admin().getCodeTemplates(null).stream().noneMatch(t -> t.getId().equals(template.getId())), "template after removal");
    }

    @Test
    @DisplayName("creates, updates, reads and removes an alert")
    void alert() throws Exception {
        AlertModel alert = new AlertModel(new DefaultTrigger(), new AlertActionGroup());
        alert.setName("Smoke alert " + suffix());
        admin().createAlert(alert);
        try {
            assertEquals(alert.getName(), admin().getAlert(alert.getId()).getName(), "alert after create");

            alert.setName(alert.getName() + " renamed");
            admin().updateAlert(alert);
            assertEquals(alert.getName(), admin().getAlert(alert.getId()).getName(), "alert after update");

            assertTrue(admin().getAlerts(null).stream().anyMatch(a -> a.getId().equals(alert.getId())),
                    "alert listed among all alerts");
        } finally {
            admin().removeAlert(alert.getId());
        }

        ClientException missing = assertThrows(ClientException.class, () -> admin().getAlert(alert.getId()), "alert after removal");
        assertTrue(missing.getMessage().contains("404"), () -> "error for the removed alert: " + missing.getMessage());
    }

    @Test
    @DisplayName("saves and restores the global scripts")
    void globalScripts() throws Exception {
        Map<String, String> original = admin().getGlobalScripts();
        Map<String, String> changed = new HashMap<>(original);
        String script = "// smoke test " + suffix() + "\nreturn;";
        changed.put("Deploy", script);
        try {
            admin().setGlobalScripts(changed);
            assertEquals(script, admin().getGlobalScripts().get("Deploy"));
        } finally {
            admin().setGlobalScripts(original);
        }

        assertEquals(original, admin().getGlobalScripts(), "global scripts after restoring");
    }

    @Test
    @DisplayName("saves and restores the server settings")
    void serverSettings() throws Exception {
        ServerSettings settings = admin().getServerSettings();
        String original = settings.getSmtpTimeout();
        try {
            settings.setSmtpTimeout("54321");
            admin().setServerSettings(settings);
            assertEquals("54321", admin().getServerSettings().getSmtpTimeout());
        } finally {
            settings.setSmtpTimeout(original);
            admin().setServerSettings(settings);
        }

        assertEquals(original, admin().getServerSettings().getSmtpTimeout(), "SMTP timeout after restoring");
    }

    @Test
    @DisplayName("saves and restores the configuration map")
    void configurationMap() throws Exception {
        Map<String, ConfigurationProperty> original = admin().getConfigurationMap();
        Map<String, ConfigurationProperty> changed = new HashMap<>(original);
        String key = "smoke." + suffix();
        changed.put(key, new ConfigurationProperty("value", "added by the smoke tests"));
        try {
            admin().setConfigurationMap(changed);
            ConfigurationProperty saved = admin().getConfigurationMap().get(key);
            assertNotNull(saved, key + " in the configuration map");
            assertEquals("value", saved.getValue());
            assertEquals("added by the smoke tests", saved.getComment());
        } finally {
            admin().setConfigurationMap(original);
        }

        assertNull(admin().getConfigurationMap().get(key), key + " after restoring");
    }

    /**
     * Request bodies are serialized by XStream, which rejects the JDK's immutable collections, so
     * the sets and lists sent to the server have to be mutable ones.
     */
    private static Set<String> ids(String... ids) {
        return new HashSet<>(List.of(ids));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static Client admin() {
        return SharedServer.get().client();
    }
}
