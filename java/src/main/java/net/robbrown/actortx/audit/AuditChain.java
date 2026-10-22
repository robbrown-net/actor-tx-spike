package net.robbrown.actortx.audit;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.robbrown.actortx.audit.AuditLog.Head;
import net.robbrown.actortx.audit.AuditLog.Verification;
import net.robbrown.actortx.audit.AuditLog.Verification.Broken;
import net.robbrown.actortx.audit.AuditLog.Verification.Intact;
import net.robbrown.actortx.events.Event;

/**
 * The codec of the hash chain: how a record is sealed, parsed and checked. A record is one line of
 * canonical JSON (keys sorted); its hash is SHA-256 over the previous record's hash followed by the
 * canonical body, which includes the previous hash in its {@code prev} field.
 */
final class AuditChain {

  /** The head of an empty log: the hash the first record commits to. */
  static final String GENESIS = "0".repeat(64);

  /** The head of an empty log. */
  static final Head EMPTY = new Head(0, GENESIS);

  private static final String HASH = "hash";
  private static final String MALFORMED = "malformed";
  private static final ObjectMapper JSON = mapper();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  /** A record ready to append: its line, newline included, and its hash. */
  record Sealed(String line, String hash) {}

  private AuditChain() {}

  private static ObjectMapper mapper() {
    // NaN and infinities must fail to encode, not turn into the text "NaN" in the log
    JsonFactory factory =
        JsonFactory.builder().disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS).build();
    return new ObjectMapper(factory)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  /**
   * Seal the next record of a log: its canonical form, and the hash that commits to it.
   *
   * @param head the log's head before this record
   * @param event what happened
   * @return the line to append and the new head hash
   */
  static Sealed seal(Head head, Event event) {
    Map<String, Object> record = normalise(body(head, event));
    String hash = digest(head.hash(), record);
    record.put(HASH, hash);
    return new Sealed(encode(record) + "\n", hash);
  }

  private static Map<String, Object> body(Head head, Event event) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("n", head.records() + 1);
    body.put("seq", event.seq());
    body.put("at", event.atMs());
    body.put("tx", event.txId());
    body.put("actor", event.actor());
    body.put("kind", event.kind());
    body.put("message", CardScrubber.scrub(event.message()));
    body.put("data", CardScrubber.scrub(event.data()));
    body.put("prev", head.hash());
    return body;
  }

  /**
   * The record as it reads back from its own JSON. This is the form that is hashed, so the writer
   * and the verifier compute over the same text whatever number types the values had.
   */
  private static Map<String, Object> normalise(Map<String, Object> body) {
    String text = encode(body);
    return Unchecked.get(() -> JSON.readValue(text, OBJECT));
  }

  static String encode(Object value) {
    return Unchecked.get(() -> JSON.writeValueAsString(value));
  }

  /**
   * SHA-256 over the previous hash and the canonical record (which contains that previous hash).
   */
  static String digest(String prev, Map<String, Object> record) {
    MessageDigest sha = Unchecked.get(() -> MessageDigest.getInstance("SHA-256"));
    return HexFormat.of().formatHex(sha.digest((prev + encode(record)).getBytes(UTF_8)));
  }

  /**
   * Check a whole log file. A missing file is empty and intact.
   *
   * @param path the log
   * @return intact with its head, or broken at the first record that fails
   */
  static Verification verify(Path path) {
    return scan(lines(path));
  }

  /**
   * The head of a log that must be intact to continue.
   *
   * @param path the log
   * @return its head
   * @throws AuditException if the log fails verification
   */
  static Head resume(Path path) {
    return switch (verify(path)) {
      case Intact intact -> intact.head();
      case Broken broken ->
          throw new AuditException(
              "audit log "
                  + path
                  + " fails verification at record "
                  + broken.atRecord()
                  + ": "
                  + broken.reason());
    };
  }

  /** The lines of a log file; a missing file has none. */
  static List<String> lines(Path path) {
    if (!Files.exists(path)) {
      return List.of();
    }
    return Unchecked.get(() -> new String(Files.readAllBytes(path), UTF_8).lines().toList());
  }

  /**
   * Every record of a log that has already been verified, in order.
   *
   * @param path the log
   * @return the decoded records
   */
  static List<Map<String, Object>> records(Path path) {
    return lines(path).stream().map(line -> parse(line).orElseThrow()).toList();
  }

  static Verification scan(List<String> lines) {
    Head head = EMPTY;
    for (int i = 0; i < lines.size(); i++) {
      Verification step = advance(head, lines.get(i), i + 1L);
      if (!(step instanceof Intact intact)) {
        return step;
      }
      head = intact.head();
    }
    return new Intact(head);
  }

  private static Verification advance(Head head, String line, long at) {
    Optional<Map<String, Object>> parsed = parse(line);
    if (parsed.isEmpty()) {
      return new Broken(at, MALFORMED);
    }
    Map<String, Object> record = parsed.get();
    if (!(record.remove(HASH) instanceof String stored)) {
      return new Broken(at, MALFORMED);
    }
    return flaw(record, stored, head)
        .<Verification>map(reason -> new Broken(at, reason))
        .orElseGet(() -> new Intact(new Head(head.records() + 1, stored)));
  }

  /** Why a record does not follow its predecessor, if it does not. */
  private static Optional<String> flaw(Map<String, Object> record, String stored, Head head) {
    if (!sameNumber(record.get("n"), head.records() + 1)) {
      return Optional.of("out_of_sequence");
    }
    if (!Objects.equals(record.get("prev"), head.hash())) {
      return Optional.of("chain_broken");
    }
    if (!digest(head.hash(), record).equals(stored)) {
      return Optional.of("hash_mismatch");
    }
    return Optional.empty();
  }

  private static boolean sameNumber(Object value, long expected) {
    return value instanceof Number number && number.toString().equals(Long.toString(expected));
  }

  /** A line as a JSON object, or empty if it is not one. */
  static Optional<Map<String, Object>> parse(String line) {
    try {
      return Optional.ofNullable(JSON.readValue(line, OBJECT));
    } catch (JsonProcessingException notAnObject) {
      return Optional.empty();
    }
  }
}
