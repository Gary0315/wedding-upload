package com.wedding.upload.drive;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.wedding.upload.config.DriveProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class GoogleDriveService {

    private final Drive drive;
    private final DriveProperties properties;

    public GoogleDriveService(Drive drive, DriveProperties properties) {
        this.drive = drive;
        this.properties = properties;
    }

    public String upload(String name, String mimeType, byte[] data) throws IOException {
        File metadata = new File();
        metadata.setName(name);
        if (properties.folderId() != null && !properties.folderId().isBlank()) {
            metadata.setParents(List.of(properties.folderId()));
        }

        ByteArrayContent content = new ByteArrayContent(mimeType, data);

        File uploaded = drive.files().create(metadata, content)
                .setSupportsAllDrives(true)
                .setFields("id, name, webViewLink")
                .execute();

        return uploaded.getId();
    }
}
