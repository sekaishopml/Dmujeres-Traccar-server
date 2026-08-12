/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MobileMessageTest {

    @Test
    public void testJacksonRoundtrip() throws Exception {
        MobileMessage message = new MobileMessage();
        message.setId(7);
        message.setDeviceId(12);
        message.setMessageId("01J00000000000000000000000");
        message.setSequence(18452);
        message.setStatus("accepted");
        message.setPayloadHash("hash");
        message.setCreated(new Date(1000));
        message.setUpdated(new Date(2000));

        MobileMessage parsed = new ObjectMapper().readValue(
                new ObjectMapper().writeValueAsString(message), MobileMessage.class);

        assertEquals(message.getId(), parsed.getId());
        assertEquals(message.getDeviceId(), parsed.getDeviceId());
        assertEquals(message.getMessageId(), parsed.getMessageId());
        assertEquals(message.getSequence(), parsed.getSequence());
        assertEquals(message.getStatus(), parsed.getStatus());
        assertNull(parsed.getPositionId());
        assertEquals(message.getPayloadHash(), parsed.getPayloadHash());
        assertEquals(message.getCreated(), parsed.getCreated());
        assertEquals(message.getUpdated(), parsed.getUpdated());
    }

}
