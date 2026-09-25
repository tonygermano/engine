// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Mitch Gaffigan <mitch@gaffigan.net>

package com.mirth.connect.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FilePermissionUtilTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testCreatesMissingFileOwnerOnly() throws Exception {
        Path path = temporaryFolder.getRoot().toPath().resolve("nested/keystore.p12");

        FilePermissionUtil.createOwnerOnlyFile(path);

        assertTrue(Files.exists(path));
        assertPermissions(path);
    }

    @Test
    public void testRestrictsExistingFile() throws Exception {
        File file = temporaryFolder.newFile("keystore.p12");
        assumeTrue(file.setReadable(true, false));

        Path path = file.toPath();
        FilePermissionUtil.createOwnerOnlyFile(path);

        assertPermissions(path);
    }

    @Test
    public void testPreservesExistingContent() throws Exception {
        File file = temporaryFolder.newFile("keystore.p12");

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(new byte[] { 1, 2, 3 });
        }

        FilePermissionUtil.createOwnerOnlyFile(file.toPath());

        assertEquals(3, file.length());
    }

    private void assertPermissions(Path path) throws Exception {
        assumeTrue(Files.getFileAttributeView(path, PosixFileAttributeView.class) != null);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
    }
}
