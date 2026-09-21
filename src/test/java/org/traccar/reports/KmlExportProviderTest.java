package org.traccar.reports;

import org.junit.jupiter.api.Test;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.Storage;

import javax.xml.stream.XMLStreamException;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.text.SimpleDateFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class KmlExportProviderTest {

    private static final String GX_NS = "http://www.google.com/kml/ext/2.2";

    private Position position(double lat, double lon, long fixTime) {
        Position position = new Position();
        position.setLatitude(lat);
        position.setLongitude(lon);
        position.setAltitude(30.75);
        position.setFixTime(new Date(fixTime));
        return position;
    }

    private String generate(Storage storage) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        TimeZone defaultTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            new KmlExportProvider(storage).generate(
                    outputStream, 1, 0, new Date(0), new Date(600_000));
        } finally {
            TimeZone.setDefault(defaultTimeZone);
        }
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    private Storage storageFor(List<Position> positions) throws Exception {
        Storage storage = mock(Storage.class);
        Device device = new Device();
        device.setName("A & B <test>");
        when(storage.getObject(eq(Device.class), any())).thenReturn(device);
        when(storage.getObjectsStream(eq(Position.class), any())).thenReturn(positions.stream());
        return storage;
    }

    @Test
    public void testGenerateOutput() throws Exception {
        Storage storage = storageFor(List.of(position(10.5, 20.25, 0)));

        String result = generate(storage);
        String expected = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\">"
                + "<Document>"
                + "<name>A &amp; B &lt;test></name>"
                + "<Placemark>"
                + "<name>1970-01-01 00:00 - 1970-01-01 00:10</name>"
                + "<gx:Track xmlns:gx=\"" + GX_NS + "\">"
                + "<altitudeMode>absolute</altitudeMode>"
                + "<when>1970-01-01T00:00:00Z</when>"
                + "<gx:coord>20.250000 10.500000 30.750000</gx:coord>"
                + "</gx:Track>"
                + "<LineString>"
                + "<extrude>1</extrude>"
                + "<tessellate>1</tessellate>"
                + "<altitudeMode>absolute</altitudeMode>"
                + "<coordinates>20.250000,10.500000,30.750000</coordinates>"
                + "</LineString>"
                + "</Placemark>"
                + "</Document>"
                + "</kml>";

        assertEquals(expected, result);
    }

    @Test
    public void testTrackWhenCoordPairsMatchPositions() throws Exception {
        List<Position> positions = new ArrayList<>();
        long base = 1_700_000_000_000L;
        positions.add(position(10.0, 20.0, base));
        positions.add(position(10.001, 20.001, base + 30_000));
        positions.add(position(10.002, 20.002, base + 60_000));
        Storage storage = storageFor(positions);

        String result = generate(storage);

        int whenCount = result.split("<when>", -1).length - 1;
        int coordCount = result.split("<gx:coord>", -1).length - 1;
        assertEquals(3, whenCount);
        assertEquals(3, coordCount);

        // Cada posición debe tener su timestamp original exacto (sin interpolar)
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertTrue(result.contains("<when>" + iso.format(new Date(base)) + "</when>"));
        assertTrue(result.contains("<when>" + iso.format(new Date(base + 30_000)) + "</when>"));
        assertTrue(result.contains("<when>" + iso.format(new Date(base + 60_000)) + "</when>"));

        // Coordenadas en el mismo orden que los when
        assertTrue(result.contains("<gx:coord>20.000000 10.000000 30.750000</gx:coord>"));
        assertTrue(result.contains("<gx:coord>20.001000 10.001000 30.750000</gx:coord>"));
        assertTrue(result.contains("<gx:coord>20.002000 10.002000 30.750000</gx:coord>"));

        // LineString de compatibilidad intacto
        assertTrue(result.contains("<coordinates>20.000000,10.000000,30.750000 "
                + "20.001000,10.001000,30.750000 20.002000,10.002000,30.750000</coordinates>"));
    }

    private static org.w3c.dom.Document parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testOutputIsParseableXml() throws Exception {
        List<Position> positions = new ArrayList<>();
        long base = 1_700_000_000_000L;
        positions.add(position(1.0, 2.0, base));
        positions.add(position(1.1, 2.1, base + 5000));
        Storage storage = storageFor(positions);

        String result = generate(storage);

        var document = parse(result);
        var trackNodes = document.getElementsByTagNameNS(GX_NS, "Track");
        assertEquals(1, trackNodes.getLength());
        var whenNodes = document.getElementsByTagName("when");
        var coordNodes = document.getElementsByTagNameNS(GX_NS, "coord");
        assertEquals(2, whenNodes.getLength());
        assertEquals(2, coordNodes.getLength());
    }

    @Test
    public void testEmptyTrackIsValid() throws Exception {
        Storage storage = storageFor(List.of());
        String result = generate(storage);
        var document = parse(result);
        assertEquals(1, document.getElementsByTagNameNS(GX_NS, "Track").getLength());
        assertEquals(0, document.getElementsByTagName("when").getLength());
        assertTrue(result.contains("<LineString>"));
    }

}
