package com.pjr22.tripweather.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

@Component
public class KmzExporter implements RouteExporter {

    private final KmlExporter kmlExporter;

    public KmzExporter(KmlExporter kmlExporter) {
        this.kmlExporter = kmlExporter;
    }

    @Override public String formatId() { return "kmz"; }
    @Override public String contentType() { return "application/vnd.google-earth.kmz"; }
    @Override public String fileExtension() { return "kmz"; }
    @Override public boolean requiresWeather() { return false; }

    @Override
    public byte[] write(ExportContext context) {
        byte[] kml = kmlExporter.write(context);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            // Google Earth looks for doc.kml at the root of the archive.
            zip.putNextEntry(new ZipEntry("doc.kml"));
            zip.write(kml);
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write KMZ", e);
        }
        return baos.toByteArray();
    }
}
