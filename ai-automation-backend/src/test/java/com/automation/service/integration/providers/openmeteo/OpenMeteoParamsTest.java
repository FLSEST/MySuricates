package com.automation.service.integration.providers.openmeteo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenMeteoParamsTest {

    private static Map<String, Object> coords(Object lat, Object lon) {
        Map<String, Object> p = new HashMap<>();
        p.put("latitude", lat);
        p.put("longitude", lon);
        return p;
    }

    @Test
    void forecastQuery_defaults() {
        String q = OpenMeteoParams.forecastQuery(coords(47.3769, 8.5417));

        assertThat(q).startsWith("latitude=47.3769&longitude=8.5417&forecast_days=7&hourly=")
                .contains("&hourly=" + OpenMeteoParams.DEFAULT_HOURLY)
                .contains("&daily=" + OpenMeteoParams.DEFAULT_DAILY)
                .contains("&current=" + OpenMeteoParams.DEFAULT_CURRENT)
                .endsWith("&timezone=auto");
    }

    @Test
    void forecastQuery_customValuesStringsAndUnits() {
        Map<String, Object> p = coords("-33.9", "18.42");
        p.put("days", "16");
        p.put("hourly", "temperature_2m, rain");
        p.put("daily", "");
        p.put("current", List.of("is_day"));
        p.put("timezone", "Europe/Zurich");
        p.put("temperature_unit", "Fahrenheit");
        p.put("wind_speed_unit", "kn");

        String q = OpenMeteoParams.forecastQuery(p);

        assertThat(q).isEqualTo("latitude=-33.9&longitude=18.42&forecast_days=16&hourly=temperature_2m,rain"
                + "&current=is_day&timezone=Europe%2FZurich&temperature_unit=fahrenheit&wind_speed_unit=kn");
    }

    @Test
    void forecastQuery_tinyCoordinatesStayPlain() {
        assertThat(OpenMeteoParams.forecastQuery(coords(0.00001, 0))).startsWith("latitude=0.00001&longitude=0&");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-90.0001, 90.5, Double.NaN})
    void latitude_outOfRange(double lat) {
        assertThatThrownBy(() -> OpenMeteoParams.forecastQuery(coords(lat, 8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.5, 181})
    void longitude_outOfRange(double lon) {
        assertThatThrownBy(() -> OpenMeteoParams.forecastQuery(coords(47, lon)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    void coordinates_boundariesAndMissingOrText() {
        assertThat(OpenMeteoParams.forecastQuery(coords(-90, 180))).startsWith("latitude=-90&longitude=180&");
        assertThatThrownBy(() -> OpenMeteoParams.forecastQuery(coords(null, 8))).hasMessageContaining("latitude");
        assertThatThrownBy(() -> OpenMeteoParams.forecastQuery(coords("Zürich", 8))).hasMessageContaining("number");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "17", "-1", "3.5", "viele"})
    void days_outOfRange(String days) {
        Map<String, Object> p = coords(47, 8);
        p.put("days", days);
        assertThatThrownBy(() -> OpenMeteoParams.forecastQuery(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days");
    }

    /** ac639b39: 4294967297 used to wrap around to 1 day, and 5.0 passed while "5.0" failed. */
    @Test
    void days_areReadLikeTheirText() {
        assertThat(OpenMeteoParams.days(" 3 ")).isEqualTo(3);
        assertThatThrownBy(() -> OpenMeteoParams.days(4_294_967_297L))
                .hasMessage("Parameter 'days' must be a whole number: 4294967297");
        assertThatThrownBy(() -> OpenMeteoParams.days(5.0))
                .hasMessage("Parameter 'days' must be a whole number: 5.0");
    }

    @Test
    void days_numberParam() {
        assertThat(OpenMeteoParams.days(1)).isEqualTo(1);
        assertThat(OpenMeteoParams.days(16)).isEqualTo(16);
        assertThatThrownBy(() -> OpenMeteoParams.days(2.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"temperature_2m&x=1", "Temp", "a b", "../x"})
    void variables_rejectInjection(String hourly) {
        Map<String, Object> p = coords(47, 8);
        p.put("hourly", hourly);
        assertThatThrownBy(() -> OpenMeteoParams.forecastQuery(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hourly");
    }

    @Test
    void timezoneAndUnits_rejectGarbage() {
        Map<String, Object> tz = coords(47, 8);
        tz.put("timezone", "Europe/Zurich&x=1");
        assertThatThrownBy(() -> OpenMeteoParams.forecastQuery(tz)).hasMessageContaining("timezone");

        Map<String, Object> unit = coords(47, 8);
        unit.put("precipitation_unit", "liter");
        assertThatThrownBy(() -> OpenMeteoParams.forecastQuery(unit)).hasMessageContaining("precipitation_unit");
    }

    @Test
    void geocodeQuery_defaultsAndEncoding() {
        assertThat(OpenMeteoParams.geocodeQuery(Map.of("name", " Zürich ")))
                .isEqualTo("name=Z%C3%BCrich&count=10&language=de&format=json");
        assertThat(OpenMeteoParams.geocodeQuery(Map.of("name", "New York", "count", 3, "language", "EN")))
                .isEqualTo("name=New+York&count=3&language=en&format=json");
    }

    @Test
    void geocodeQuery_validation() {
        assertThatThrownBy(() -> OpenMeteoParams.geocodeQuery(Map.of("name", "Z"))).hasMessageContaining("name");
        assertThatThrownBy(() -> OpenMeteoParams.geocodeQuery(Map.of())).hasMessageContaining("name");
        assertThatThrownBy(() -> OpenMeteoParams.geocodeQuery(Map.of("name", "Bern", "count", 0))).hasMessageContaining("count");
        assertThatThrownBy(() -> OpenMeteoParams.geocodeQuery(Map.of("name", "Bern", "count", 101))).hasMessageContaining("count");
        assertThatThrownBy(() -> OpenMeteoParams.geocodeQuery(Map.of("name", "Bern", "language", "deu"))).hasMessageContaining("language");
    }
}
