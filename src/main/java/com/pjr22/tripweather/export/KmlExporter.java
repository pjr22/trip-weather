package com.pjr22.tripweather.export;

import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.springframework.stereotype.Component;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.WaypointDto;

@Component
public class KmlExporter implements RouteExporter {

    private static final String KML_NS = "http://www.opengis.net/kml/2.2";

    // KML colors are aabbggrr (alpha-blue-green-red). #0066cc with full alpha → ffcc6600.
    private static final String ROUTE_LINE_COLOR = "ffcc6600";
    private static final String ROUTE_LINE_WIDTH = "4";

    private static final String STYLE_ROUTE = "routeLine";
    private static final String STYLE_WAYPOINT = "waypointIcon";
    private static final String WAYPOINT_ICON_HREF = "http://maps.google.com/mapfiles/kml/paddle/red-circle.png";

    private final XMLOutputFactory xmlFactory = XMLOutputFactory.newInstance();

    @Override public String formatId() { return "kml"; }
    @Override public String contentType() { return "application/vnd.google-earth.kml+xml"; }
    @Override public String fileExtension() { return "kml"; }
    @Override public boolean requiresWeather() { return false; }

    @Override
    public byte[] write(ExportContext context) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            XMLStreamWriter writer = xmlFactory.createXMLStreamWriter(baos, "UTF-8");
            writeKml(writer, context);
            writer.flush();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to write KML", e);
        }
        return baos.toByteArray();
    }

    void writeKml(XMLStreamWriter writer, ExportContext context) throws XMLStreamException {
        RouteDto route = context.getRoute();
        List<WaypointDto> waypoints = route.getWaypoints();
        List<List<Double>> geometry = context.getRouteData().getGeometry();

        writer.writeStartDocument("UTF-8", "1.0");
        writer.writeStartElement("kml");
        writer.writeDefaultNamespace(KML_NS);

        writer.writeStartElement("Document");
        writeText(writer, "name", route.getName());

        writeLineStyle(writer);
        writeWaypointStyle(writer);

        writeRoutePlacemark(writer, route.getName(), geometry);
        for (WaypointDto wp : waypoints) {
            writeWaypointPlacemark(writer, wp);
        }

        writer.writeEndElement(); // Document
        writer.writeEndElement(); // kml
        writer.writeEndDocument();
    }

    private void writeLineStyle(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("Style");
        writer.writeAttribute("id", STYLE_ROUTE);
        writer.writeStartElement("LineStyle");
        writeText(writer, "color", ROUTE_LINE_COLOR);
        writeText(writer, "width", ROUTE_LINE_WIDTH);
        writer.writeEndElement(); // LineStyle
        writer.writeEndElement(); // Style
    }

    private void writeWaypointStyle(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("Style");
        writer.writeAttribute("id", STYLE_WAYPOINT);
        writer.writeStartElement("IconStyle");
        writer.writeStartElement("Icon");
        writeText(writer, "href", WAYPOINT_ICON_HREF);
        writer.writeEndElement(); // Icon
        writer.writeEndElement(); // IconStyle
        writer.writeEndElement(); // Style
    }

    private void writeRoutePlacemark(XMLStreamWriter writer, String name, List<List<Double>> geometry)
            throws XMLStreamException {
        writer.writeStartElement("Placemark");
        writeText(writer, "name", name);
        writeText(writer, "styleUrl", "#" + STYLE_ROUTE);
        writer.writeStartElement("LineString");
        writeText(writer, "tessellate", "1");
        writeText(writer, "altitudeMode", "clampToGround");
        writer.writeStartElement("coordinates");
        StringBuilder coords = new StringBuilder();
        for (List<Double> point : geometry) {
            if (point.size() < 2) continue;
            if (coords.length() > 0) coords.append(' ');
            coords.append(point.get(0)).append(',').append(point.get(1));
            if (point.size() >= 3) coords.append(',').append(point.get(2));
        }
        writer.writeCharacters(coords.toString());
        writer.writeEndElement(); // coordinates
        writer.writeEndElement(); // LineString
        writer.writeEndElement(); // Placemark
    }

    private void writeWaypointPlacemark(XMLStreamWriter writer, WaypointDto wp) throws XMLStreamException {
        writer.writeStartElement("Placemark");
        writeText(writer, "name", wp.getLocationName() != null ? wp.getLocationName()
                : "Waypoint " + wp.getSequence());
        writeText(writer, "styleUrl", "#" + STYLE_WAYPOINT);

        String description = buildDescription(wp);
        if (!description.isEmpty()) {
            writer.writeStartElement("description");
            writer.writeCData(description);
            writer.writeEndElement();
        }

        writer.writeStartElement("Point");
        writer.writeStartElement("coordinates");
        StringBuilder coords = new StringBuilder();
        coords.append(wp.getLongitude()).append(',').append(wp.getLatitude());
        if (wp.getElevation() != null) coords.append(',').append(wp.getElevation());
        writer.writeCharacters(coords.toString());
        writer.writeEndElement(); // coordinates
        writer.writeEndElement(); // Point
        writer.writeEndElement(); // Placemark
    }

    static String buildDescription(WaypointDto wp) {
        StringBuilder sb = new StringBuilder();
        if (wp.getDate() != null && !wp.getDate().isBlank()
                && wp.getTime() != null && !wp.getTime().isBlank()) {
            sb.append("Arrival: ").append(wp.getDate()).append(' ').append(wp.getTime());
            if (wp.getTimezone() != null && !wp.getTimezone().isBlank()) {
                sb.append(" (").append(wp.getTimezone()).append(')');
            }
        }
        if (wp.getDurationMin() != null && wp.getDurationMin() > 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("Stop duration: ").append(wp.getDurationMin()).append(" min");
        }
        return sb.toString();
    }

    private static void writeText(XMLStreamWriter writer, String element, String value)
            throws XMLStreamException {
        if (value == null) return;
        writer.writeStartElement(element);
        writer.writeCharacters(value);
        writer.writeEndElement();
    }
}
