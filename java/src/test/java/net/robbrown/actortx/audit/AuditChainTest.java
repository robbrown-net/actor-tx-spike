package net.robbrown.actortx.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.audit.AuditLog.Head;
import net.robbrown.actortx.audit.AuditLog.Verification.Intact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditChainTest {

  private static final String GENESIS = "0".repeat(64);

  @TempDir Path dir;

  @Test
  void objectsAreEncodedAsCanonicalJsonWithSortedKeys() {
    assertThat(AuditChain.encode(Map.of("b", 1, "a", Map.of("y", 2, "x", 3))))
        .isEqualTo("{\"a\":{\"x\":3,\"y\":2},\"b\":1}");
  }

  @Test
  void theDigestIsSha256OverThePreviousHashAndTheCanonicalRecord() throws Exception {
    String expected =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(("abc" + "{\"a\":1}").getBytes(StandardCharsets.UTF_8)));
    assertThat(AuditChain.digest("abc", Map.of("a", 1))).isEqualTo(expected);
  }

  @Test
  void onlyJsonObjectsParseAsRecords() {
    assertThat(AuditChain.parse("{\"a\":1}")).contains(Map.of("a", 1));
    assertThat(AuditChain.parse("null")).isEmpty();
    assertThat(AuditChain.parse("[1]")).isEmpty();
    assertThat(AuditChain.parse("")).isEmpty();
  }

  @Test
  void anEmptyScanIsIntactAtGenesis() {
    assertThat(AuditChain.scan(List.of())).isEqualTo(new Intact(new Head(0, GENESIS)));
  }

  @Test
  void aMissingLogHasNoLinesAndNoRecords() {
    Path absent = dir.resolve("absent.log");
    assertThat(AuditChain.lines(absent)).isEmpty();
    assertThat(AuditChain.records(absent)).isEmpty();
  }

  @Test
  void aLogThatCannotBeReadIsAnAuditFailure() {
    assertThatThrownBy(() -> AuditChain.lines(dir)).isInstanceOf(AuditException.class);
  }

  @Test
  void resumingAnIntactLogGivesItsHead() throws IOException {
    Path log = dir.resolve("audit.log");
    Files.writeString(log, "");
    assertThat(AuditChain.resume(log)).isEqualTo(new Head(0, GENESIS));
  }

  @Test
  void resumingABrokenLogIsRefused() throws IOException {
    Path log = dir.resolve("audit.log");
    Files.writeString(log, "not json\n");
    assertThatThrownBy(() -> AuditChain.resume(log))
        .isInstanceOf(AuditException.class)
        .hasMessageContaining("fails verification at record 1: malformed");
  }

  @Test
  void aSealedRecordIsOneLineEndingWithItsHash() {
    AuditChain.Sealed sealed =
        AuditChain.seal(AuditChain.EMPTY, AuditFixtures.event(1, "tx-1", "step_succeeded"));
    assertThat(sealed.line()).endsWith("}\n").contains("\"hash\":\"" + sealed.hash() + "\"");
    assertThat(sealed.line().strip().lines()).hasSize(1);
    assertThat(sealed.hash()).hasSize(64);
  }
}
