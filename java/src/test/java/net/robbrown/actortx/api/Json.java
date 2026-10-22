package net.robbrown.actortx.api;

import java.util.List;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Test helper: typed JSON over {@link TestRestTemplate} without raw types or unchecked casts at
 * each call.
 */
final class Json {

  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
      new ParameterizedTypeReference<>() {};

  private Json() {}

  static ResponseEntity<Map<String, Object>> post(TestRestTemplate http, String path, Object body) {
    return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body), MAP);
  }

  static ResponseEntity<Map<String, Object>> get(TestRestTemplate http, String path) {
    return http.exchange(path, HttpMethod.GET, null, MAP);
  }

  static List<Map<String, Object>> list(TestRestTemplate http, String path) {
    return http.exchange(path, HttpMethod.GET, null, LIST).getBody();
  }

  @SuppressWarnings("unchecked")
  static List<String> strings(Object value) {
    return (List<String>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> maps(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
