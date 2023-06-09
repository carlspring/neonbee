package io.neonbee.data;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vertx.core.buffer.Buffer;

class DataQueryTest {

    private DataQuery query;

    @BeforeEach
    void setUp() {
        query = new DataQuery();
    }

    @Test
    @DisplayName("setQuery should set a query and reset parameters to null")
    @SuppressWarnings("deprecation")
    void testSetQuery() {
        query.parameters = Collections.emptyMap();
        query.setQuery("name=Hodor");
        assertThat(query.getQuery()).isEqualTo("name=Hodor");
        assertThat(query.parameters).isNotNull();
    }

    @Test
    @DisplayName("getQuery should return the parameters joined to a String if parameters is non null")
    @SuppressWarnings("deprecation")
    void testGetQuery2() {
        query.parameters = Map.of("Hodor", List.of("Hodor"));
        assertThat(query.getQuery()).isEqualTo("Hodor=Hodor");

        query.parameters = Map.of("Hodor", List.of("Hodor", "Hodor2"));
        assertThat(query.getQuery()).isEqualTo("Hodor=Hodor&Hodor=Hodor2");
    }

    @Test
    @DisplayName("getParameters should return a Map with the query parameters")
    @SuppressWarnings("deprecation")
    void testGetParameters() {
        query.setQuery("Hodor=Hodor&Jon=Snow&Hodor=Hodor2");
        Map<String, List<String>> expected = Map.of("Hodor", List.of("Hodor", "Hodor2"), "Jon", List.of("Snow"));
        assertThat(query.getParameters()).containsExactlyEntriesIn(expected);
    }

    @Test
    @DisplayName("getParameterValues should return a List with the values for a given parameter")
    @SuppressWarnings("deprecation")
    void testGetParameterValues() {
        query.setQuery("Hodor=Hodor&Jon=Snow&Hodor=Hodor2");
        List<String> expected = List.of("Hodor", "Hodor2");
        assertThat(query.getParameterValues("Hodor")).containsExactlyElementsIn(expected);
    }

    @Test
    @DisplayName("getParameter should return the value for a given parameter")
    @SuppressWarnings("deprecation")
    void testGetParameter() {
        query.setQuery("Hodor=Hodor&Jon=Snow&Hodor=Hodor2&Some=Data&Empty=&AlsoEmpty&Test=123");
        assertThat(query.getParameter("Hodor")).isEqualTo("Hodor");
        assertThat(query.getParameter("Jon")).isEqualTo("Snow");
        assertThat(query.getParameter("Some")).isEqualTo("Data");
        assertThat(query.getParameter("Empty")).isEqualTo("");
        assertThat(query.getParameter("AlsoEmpty")).isEqualTo("");
        assertThat(query.getParameter("Test")).isEqualTo("123");
    }

    /**
     * The following query is parsed into the wrong key value pairs.<br>
     * encoded: q%3D%26%C3%A4=%C3%A4%26=q&$filter=char%20=%20%27%26%27 <br>
     * decoded: q=&ä=ä&=q&$filter=char = '&'" <br>
     * this should be parsed into the following key value pairs: <br>
     * q=&ä -> ä&=q <br>
     * $filter -> char = '&'<br>
     * also see: {@link io.neonbee.data.DataQueryTest#testSetRawQuery}
     */
    @Test
    @DisplayName("Test that verifies the method setQuery can not work properly")
    @SuppressWarnings("deprecation")
    void testSetQueryNotWorkingCorrectly() {
        String queryString = "q%3D%26%C3%A4=%C3%A4%26=q&$filter=char%20=%20%27%26%27";
        query.setQuery(URLDecoder.decode(queryString, StandardCharsets.UTF_8));
        assertThat(query.getParameter("q")).isEqualTo("");
        assertThat(query.getParameter("ä")).isEqualTo("ä");
        assertThat(query.getParameter("$filter")).isEqualTo("char = '");
        assertThat(query.getParameter("'")).isEqualTo("");
        assertThat(query.getParameter("")).isEqualTo("q");
    }

    @Test
    @DisplayName("getParameter should work")
    void testSetRawQuery() {
        query.setRawQuery("q%3D%26%C3%A4=%C3%A4%26=q&$filter=char%20=%20%27%26%27");
        assertThat(query.getParameter("q=&ä")).isEqualTo("ä&=q");
        assertThat(query.getParameter("$filter")).isEqualTo("char = '&'");
    }

    @Test
    @DisplayName("getParameter should return the first value for a given parameter")
    @SuppressWarnings("deprecation")
    void testGetFirstParameter() {
        query.setQuery("Hodor=Hodor&Jon=Snow&Hodor=Hodor2");
        String expected = "Hodor";
        assertThat(query.getParameter("Hodor")).isEqualTo(expected);
    }

    @Test
    @DisplayName("addParameter should add a given parameter with value(s)")
    void testAddParameter() {
        Map<String, List<String>> expected = Map.of("Jon", List.of("Snow"));
        assertThat(query.addParameter("Jon", "Snow").parameters).isEqualTo(expected);

        expected = Map.of("Hodor", List.of("Hodor", "Hodor2"), "Jon", List.of("Snow"));
        assertThat(query.addParameter("Hodor", "Hodor", "Hodor2").parameters).isEqualTo(expected);
    }

    @Test
    @DisplayName("setParameter should set a given parameter with value(s)")
    void testSetParameter() {
        Map<String, List<String>> expected = Map.of("Jon", List.of("Snow"));
        assertThat(query.setParameter("Jon", "Snow").parameters).isEqualTo(expected);

        expected = Map.of("Hodor", List.of("Hodor", "Hodor2"), "Jon", List.of("Snow"));
        assertThat(query.setParameter("Hodor", "Hodor", "Hodor2").parameters).isEqualTo(expected);

        expected = Map.of("Hodor", List.of("Hodor", "Hodor2"), "Jon", List.of("Snow", "Know", "Nothing"));
        assertThat(query.setParameter("Jon", "Snow", "Know", "Nothing").parameters).isEqualTo(expected);
    }

    @Test
    @DisplayName("setParameter should set a given parameter with value(s)")
    @SuppressWarnings("deprecation")
    void testRemoveParameter() {
        query.parameters = Map.of("Hodor", List.of("Hodor", "Hodor2"), "Jon", List.of("Snow", "Know", "Nothing"));
        query.setQuery(query.getQuery());

        Map<String, List<String>> expected = Map.of("Hodor", List.of("Hodor", "Hodor2"));
        assertThat(query.removeParameter("Jon").parameters).isEqualTo(expected);
    }

    @Test
    @DisplayName("test that setRawQuery from getRawQuery create equal DataQuery objects")
    void testGetSetRawQuery() {
        query.parameters = Map.of("q=&ä", List.of("ä&=q", "=&"), "$filter", List.of("char = '&'"));
        DataQuery query2 = new DataQuery().setRawQuery(query.getRawQuery());

        assertThat(query2).isEqualTo(query);
    }

    @Test
    @DisplayName("parseQueryString should parse a query string correct")
    @SuppressWarnings("deprecation")
    void testParseQueryString() {
        Map<String, List<String>> expected = Map.of("Hodor", List.of(""));
        assertThat(DataQuery.parseQueryString("Hodor")).containsExactlyEntriesIn(expected);
        assertThat(DataQuery.parseQueryString("Hodor=")).containsExactlyEntriesIn(expected);

        expected = Map.of("Hodor", List.of("Hodor"));
        assertThat(DataQuery.parseQueryString("Hodor=Hodor")).containsExactlyEntriesIn(expected);

        expected = Map.of("Hodor", List.of("Hodor", "Hodor2"));
        assertThat(DataQuery.parseQueryString("Hodor=Hodor&Hodor=Hodor2")).containsExactlyEntriesIn(expected);

        expected = Map.of("Hodor", List.of("Hodor", "Hodor2"), "Jon", List.of("Snow"));
        assertThat(DataQuery.parseQueryString("Hodor=Hodor&Jon=Snow&Hodor=Hodor2")).containsExactlyEntriesIn(expected);
    }

    @Test
    @DisplayName("setUriPath should not work if it contains a query")
    void testSetUriPathWithQuery() {
        Exception thrownException = assertThrows(IllegalArgumentException.class, () -> {
            new DataQuery().setUriPath("/raw/MyDataVerticle?param=value");
        });
        assertThat(thrownException.getMessage()).isEqualTo("uriPath must not contain a query");
    }

    @Test
    @DisplayName("DataQuery creation should not work if it uriPath contains a query")
    void testDataQueryCreation() {
        Exception thrownException = assertThrows(IllegalArgumentException.class, () -> {
            new DataQuery("/odata/MyNamespace.MyService/MyEntitySet?$param=value");
        });
        assertThat(thrownException.getMessage()).isEqualTo("uriPath must not contain a query");
    }

    @Test
    @DisplayName("DataQuery should have empty map when no header is passed")
    @SuppressWarnings("deprecation")
    void testEmptyHeader() {
        DataQuery query = new DataQuery("uri", "name=Hodor");
        assertThat(query.getHeaders()).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("Equals should return false with different bodies")
    @SuppressWarnings("deprecation")
    void testEqualsWithDifferentBodies() {
        DataQuery query1 = new DataQuery(DataAction.CREATE, "uri", "name=Hodor", Map.of("header1", List.of("value1")),
                Buffer.buffer("payload1"));
        assertThat(query1.copy().setBody(Buffer.buffer("payload2"))).isNotEqualTo(query1);
    }

    @Test
    @DisplayName("DataQuery should have case-insensitive headers")
    @SuppressWarnings("deprecation")
    void testCaseInsensitivityOfHeaders() {
        DataQuery query = new DataQuery("uri", "name=Hodor", Map.of("header1", List.of("value1")));
        assertThat(query.getHeaderValues("Header1")).isEqualTo(List.of("value1"));
    }

    @Test
    @DisplayName("Get DataQuery header should return null when no match in case-insensitive headers")
    @SuppressWarnings("deprecation")
    void testNonMatchOfHeaders() {
        DataQuery query = new DataQuery("uri", "name=Hodor", Map.of("header1", List.of("value1")));
        assertThat(query.getHeader("Header2")).isNull();
    }

    @Test
    @DisplayName("Set DataQuery headers should set new headers")
    @SuppressWarnings("deprecation")
    void testSetHeaders() {
        DataQuery query = new DataQuery("uri", "name=Hodor", Map.of("header1", List.of("value1")));
        query.setHeaders(Map.of("Header1", List.of("value2")));
        assertThat(query.getHeaderValues("header1")).isEqualTo(List.of("value2"));
    }

    @Test
    @DisplayName("Add DataQuery headers should add new value to existing headers")
    @SuppressWarnings("deprecation")
    void testAddToExistingHeaders() {
        DataQuery query = new DataQuery("uri", "name=Hodor", Map.of("header1", List.of("value1")));
        query.addHeader("Header1", "value2");
        assertThat(query.getHeaderValues("header1")).isEqualTo(List.of("value1", "value2"));
    }

    @Test
    @DisplayName("Add DataQuery header should create new header when it does not already exist")
    @SuppressWarnings("deprecation")
    void testAddNewHeaders() {
        DataQuery query = new DataQuery("uri", "name=Hodor", Map.of("header1", List.of("value1")));
        query.addHeader("header2", "value2");
        assertThat(query.getHeaderValues("header2")).isEqualTo(List.of("value2"));
    }

    @Test
    @DisplayName("Modifying headers in copied DataQuery should not modify headers in the original one")
    @SuppressWarnings("deprecation")
    void testHeadersChangeInCopiedQuery() {
        DataQuery query1 = new DataQuery("uri", "name=Hodor", Map.of("header1", new ArrayList<>(List.of("value1"))));
        assertThat(query1.getHeaderValues("header1")).hasSize(1);
        DataQuery query2 = query1.copy();
        query2.getHeaderValues("header1").add("value2");
        assertThat(query1.getHeaderValues("header1")).hasSize(1);
    }
}
