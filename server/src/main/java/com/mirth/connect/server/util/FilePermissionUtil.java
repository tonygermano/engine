package com.mirth.connect.server.util;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FilePermissionUtil {

    private static final Logger logger = LogManager.getLogger(FilePermissionUtil.class);

    private static final EnumSet<PosixFilePermission> POSIX_OWNER_READWRITE = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final EnumSet<AclEntryPermission> ACL_FULL_CONTROL = EnumSet.allOf(AclEntryPermission.class);

    private FilePermissionUtil() {}

    /**
     * Creates the file if it does not already exist, and restricts it so that only
     * the user the server runs as (and System/Administrators on Windows) may read or write it.
     * This is what protects files that hold key material in place of a passphrase,
     * so the file must never be created readable and then locked down afterwards;
     * on POSIX and Windows ACL, the permissions are applied as part of the create itself.
     * If the file already exists, its permissions are updated to match these restrictions.
     * 
     * @param path The path of the file to create or secure.
     * @throws IOException If an I/O error occurs during creation or path resolution.
     */
    public static void createOwnerOnlyFile(Path path) throws IOException {
        Path parentDir = path.toAbsolutePath().getParent();
        if (parentDir != null) {
            // Create missing parent directories. Does nothing if directory already exists.
            Files.createDirectories(parentDir);
        }
        FileStore parentStore = Files.getFileStore(parentDir);
        FileAttribute<?> att = null;

        if (parentStore.supportsFileAttributeView("posix")) {
            att = PosixFilePermissions.asFileAttribute(POSIX_OWNER_READWRITE);
        } else if (parentStore.supportsFileAttributeView("acl")) {
            att = getAclFileAttribute(path);
        }

        if (!Files.exists(path)) {
            if (att != null) {
                Files.createFile(path, att);
            } else {
                Files.createFile(path);
            }
        } else if (att != null) {
            try {
                Files.setAttribute(path, att.name(), att.value());
            } catch (IOException e) {
                logger.warn("Could not automatically restrict permissions on existing file: {}. "
                        + "The application may not have ownership rights or the filesystem may be read-only. "
                        + "Please ensure the file is secured manually.", path, e);
            }
        }
    }

    /*
     * Constructs a FileAttribute representing a Windows ACL.
     * Grants full control to the local Administrators group, the SYSTEM account,
     * and the file's CREATOR OWNER. This ensures the file is secured to the server 
     * process but remains manageable by system administrators and backup processes.
     */
    private static FileAttribute<List<AclEntry>> getAclFileAttribute(Path path) {
        try {
            UserPrincipalLookupService lookupService = path.getFileSystem().getUserPrincipalLookupService();

            // Resolve standard Windows identities + the placeholder OWNER principal
            UserPrincipal administratorsGroup = lookupService.lookupPrincipalByGroupName("BUILTIN\\Administrators");
            UserPrincipal localSystemAccount = lookupService.lookupPrincipalByName("NT AUTHORITY\\SYSTEM");
            UserPrincipal creatorOwnerPlaceholder = lookupService.lookupPrincipalByName("CREATOR OWNER");

            // Stream-line the creation of explicit ACL rules into an immutable list
            final List<AclEntry> cleanAcl = Stream.of(administratorsGroup, localSystemAccount, creatorOwnerPlaceholder)
                    .map(userPrincipal -> AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(userPrincipal)
                            .setPermissions(ACL_FULL_CONTROL)
                            .build())
                    .toList();

            // Wrap into a FileAttribute configuration
            return new FileAttribute<>() {
                @Override
                public String name() {
                    return "acl:acl";
                }

                @Override
                public List<AclEntry> value() {
                    return cleanAcl;
                }
            };
        } catch (Exception e) {
            logger.warn("Could not resolve Windows user principals to set strict file permissions for: {}. "
                    + "Falling back to default file creation permissions.", path, e);
            return null;
        }
    }
}