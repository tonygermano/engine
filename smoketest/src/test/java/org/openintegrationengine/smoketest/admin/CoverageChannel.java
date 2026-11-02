// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Tony Germano

package org.openintegrationengine.smoketest.admin;

import java.util.UUID;

import org.openintegrationengine.smoketest.Harness;
import org.openintegrationengine.smoketest.SharedServer;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.converters.ObjectXMLSerializer;

/**
 * Loads the raw pass-through channel the administrator API tests share. Each call gives the
 * channel a fresh id and name, so tests never collide with each other or with a channel left by
 * an earlier run.
 */
final class CoverageChannel {

    private static final String RESOURCE = "admin/channel.xml";

    private CoverageChannel() {
    }

    /**
     * Returns a new copy of the channel.
     *
     * @param purpose a few words naming the test that owns the channel, added to its name
     */
    static Channel create(String purpose) {
        // Deserializing goes through ObjectXMLSerializer, which the shared login initialises.
        SharedServer.get();
        Channel channel = ObjectXMLSerializer.getInstance().deserialize(Harness.resource(RESOURCE), Channel.class);
        String id = UUID.randomUUID().toString();
        channel.setId(id);
        channel.setName("Smoke " + purpose + " " + id.substring(0, 8));
        return channel;
    }
}
