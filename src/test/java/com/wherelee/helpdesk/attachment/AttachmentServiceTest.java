package com.wherelee.helpdesk.attachment;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AttachmentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deleteTenantFilesRemovesOnlyTheTenantDirectory() throws Exception {
        HelpdeskProperties properties = new HelpdeskProperties();
        properties.getAttachments().setDir(tempDir.toString());
        AttachmentService service = new AttachmentService(null, null, properties);

        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        Path tenantFile = tempDir.resolve(tenantId.toString())
                .resolve(UUID.randomUUID().toString()).resolve("a.txt");
        Path otherFile = tempDir.resolve(otherTenant.toString())
                .resolve(UUID.randomUUID().toString()).resolve("b.txt");
        Files.createDirectories(tenantFile.getParent());
        Files.createDirectories(otherFile.getParent());
        Files.writeString(tenantFile, "tenant");
        Files.writeString(otherFile, "other");

        service.deleteTenantFiles(tenantId);

        assertThat(tempDir.resolve(tenantId.toString())).doesNotExist();
        assertThat(otherFile).exists();
        assertThatCode(() -> service.deleteTenantFiles(UUID.randomUUID())).doesNotThrowAnyException();
    }
}
