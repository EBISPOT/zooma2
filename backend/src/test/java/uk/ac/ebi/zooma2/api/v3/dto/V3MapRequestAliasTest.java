package uk.ac.ebi.zooma2.api.v3.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** A V3 map request may call its input list {@code strings}; {@code properties} keeps working unchanged (issue #48). */
class V3MapRequestAliasTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void stringsIsAnAliasForProperties() throws Exception {
        var request = JSON.readValue("{\"strings\":[{\"textToMap\":\"liver\",\"propertyType\":\"organism part\"}]}", V3MapRequestDto.class);
        assertEquals(1, request.properties.size());
        assertEquals("liver", request.properties.get(0).textToMap);
        assertEquals("organism part", request.properties.get(0).propertyType);
    }

    @Test
    void propertiesStillWorks() throws Exception {
        var request = JSON.readValue("{\"properties\":[{\"textToMap\":\"liver\"}]}", V3MapRequestDto.class);
        assertEquals("liver", request.properties.get(0).textToMap);
        assertNull(request.properties.get(0).propertyType);
    }
}
