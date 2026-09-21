/*
 * Copyright 2022 - 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.reports;

import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public class KmlExportProvider {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Storage storage;

    @Inject
    public KmlExportProvider(Storage storage) {
        this.storage = storage;
    }

    public void generate(
            OutputStream outputStream, long deviceId, long geofenceId, Date from, Date to)
            throws StorageException, XMLStreamException {

        var device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));

        Geofence geofence = geofenceId == 0 ? null : storage.getObject(Geofence.class, new Request(
                new Columns.All(), new Condition.Equals("id", geofenceId)));

        XMLStreamWriter writer = XMLOutputFactory.newFactory()
                .createXMLStreamWriter(outputStream, StandardCharsets.UTF_8.name());

        writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
        writer.writeStartElement("kml");
        writer.writeDefaultNamespace("http://www.opengis.net/kml/2.2");
        writer.writeStartElement("Document");
        writer.writeStartElement("name");
        writer.writeCharacters(device.getName());
        writer.writeEndElement();
        writer.writeStartElement("Placemark");
        writer.writeStartElement("name");
        writer.writeCharacters(DATE_FORMAT.format(from.toInstant()) + " - " + DATE_FORMAT.format(to.toInstant()));
        writer.writeEndElement();

        // gx:Track con pares <when>/<gx:coord> emparejados (timestamps originales,
        // sin interpolar ni inventar posiciones). Se acumulan primero en memoria
        // porque el stream de la BD solo puede recorrerse una vez y el Placemark
        // del LineString compatible también lo consume.
        List<Position> trackPositions = new ArrayList<>();
        try (Stream<Position> positions = PositionUtil.getPositionsStream(storage, deviceId, from, to)
                .filter(position -> geofence == null || geofence.containsPosition(position))) {
            positions.forEach(trackPositions::add);
        }

        writer.writeStartElement("gx:Track");
        writer.writeNamespace("gx", "http://www.google.com/kml/ext/2.2");
        writer.writeStartElement("altitudeMode");
        writer.writeCharacters("absolute");
        writer.writeEndElement();
        for (Position position : trackPositions) {
            writer.writeStartElement("when");
            writer.writeCharacters(DateTimeFormatter.ISO_INSTANT.format(
                    Instant.ofEpochMilli(position.getFixTime().getTime())));
            writer.writeEndElement();
        }
        for (Position position : trackPositions) {
            writer.writeStartElement("gx:coord");
            writer.writeCharacters(String.format(
                    "%f %f %f", position.getLongitude(), position.getLatitude(), position.getAltitude()));
            writer.writeEndElement();
        }
        writer.writeEndElement();

        writer.writeStartElement("LineString");
        writer.writeStartElement("extrude");
        writer.writeCharacters("1");
        writer.writeEndElement();
        writer.writeStartElement("tessellate");
        writer.writeCharacters("1");
        writer.writeEndElement();
        writer.writeStartElement("altitudeMode");
        writer.writeCharacters("absolute");
        writer.writeEndElement();
        writer.writeStartElement("coordinates");
        String separator = "";
        for (Position position : trackPositions) {
            writer.writeCharacters(separator + String.format(
                    "%f,%f,%f", position.getLongitude(), position.getLatitude(), position.getAltitude()));
            separator = " ";
        }
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.flush();
        writer.close();
    }

}
