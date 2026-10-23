package net.robbrown.actortx.replay;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.robbrown.actortx.audit.AuditException;
import net.robbrown.actortx.audit.AuditLog;
import net.robbrown.actortx.replay.ReplayException.Reason;

/**
 * Replay the day from its log. The audit log holds the narrative and the journal: every input from
 * the outside world and enough context to judge every decision again. This class loads a day (and
 * refuses a log whose chain fails verification) and answers the questions about it: the state at a
 * moment ({@link #stateAt}), whether the day followed the rules ({@link Rules#verify}), how another
 * day compares ({@link Compare#diff}) and what a different strategy would have done ({@link
 * WhatIf#run}).
 *
 * <p>Decisions are not re-implemented for replay: the selection rule is the same pure function the
 * live system used, fed with the recorded inputs.
 */
public final class Replay {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
  private static final String LOG_SUFFIX = ".log";

  private Replay() {}

  /**
   * Load a day from an audit log file, verifying its chain first.
   *
   * @param path the log
   * @return the day
   * @throws ReplayException if the log is missing, fails verification or is malformed
   */
  public static Day load(Path path) {
    return load(path, true);
  }

  /**
   * Load a day from an audit log file.
   *
   * @param path the log
   * @param verify whether to verify the hash chain first (a log that is not JSON is refused either
   *     way)
   * @return the day
   * @throws ReplayException if the log is missing, unreadable, malformed or fails verification
   */
  public static Day load(Path path, boolean verify) {
    Optional<AuditLog.Head> head = verify ? Optional.of(intact(path)) : Optional.empty();
    return Day.of(readRecords(path), head);
  }

  /**
   * Load every regional log in a directory ({@code <dir>/*.log}), keyed by log name, verifying
   * each. A missing directory has no logs.
   *
   * @param dir the directory
   * @return the days by log name
   * @throws ReplayException if any log is refused
   */
  public static Map<String, Day> loadDir(Path dir) {
    return loadDir(dir, true);
  }

  /**
   * Load every regional log in a directory, keyed by log name.
   *
   * @param dir the directory
   * @param verify whether to verify each hash chain first
   * @return the days by log name
   * @throws ReplayException if any log is refused
   */
  public static Map<String, Day> loadDir(Path dir, boolean verify) {
    Map<String, Day> days = new TreeMap<>();
    for (Path log : logsIn(dir)) {
      days.put(name(log), load(log, verify));
    }
    return days;
  }

  /**
   * Build a day from already-decoded records (tests, other sources). Not verified.
   *
   * @param records the records, in order
   * @return the day
   */
  public static Day fromRecords(List<Map<String, Object>> records) {
    return Day.of(records, Optional.empty());
  }

  /**
   * The system as it stood at a moment.
   *
   * @param day the day
   * @param position the moment: a time, an event sequence number or an audit record number
   * @return the state up to and including that point
   */
  public static State stateAt(Day day, Position position) {
    return State.at(day, position);
  }

  /**
   * The bulk batches recorded in a day, in order.
   *
   * @param day the day
   * @return the batches
   */
  public static List<Batch> batches(Day day) {
    return Batch.of(day.records());
  }

  /**
   * The record kinds that end a transaction.
   *
   * @return the kinds, in order
   */
  public static List<String> terminalKinds() {
    return Outcome.terminalKinds();
  }

  private static AuditLog.Head intact(Path path) {
    return switch (verifyChain(path)) {
      case AuditLog.Verification.Intact intact -> intact.head();
      case AuditLog.Verification.Broken broken ->
          throw new ReplayException(
              Reason.INTEGRITY,
              path + " fails verification at record " + broken.atRecord() + ": " + broken.reason());
    };
  }

  private static AuditLog.Verification verifyChain(Path path) {
    try {
      return AuditLog.verify(path);
    } catch (AuditException unreadable) {
      throw new ReplayException(Reason.UNREADABLE, path + " cannot be read: " + unreadable);
    }
  }

  private static List<Map<String, Object>> readRecords(Path path) {
    if (!Files.exists(path)) {
      throw new ReplayException(Reason.NO_SUCH_LOG, path + " does not exist");
    }
    return readLines(path).stream().map(Replay::decode).toList();
  }

  private static List<String> readLines(Path path) {
    try {
      return Files.readAllLines(path, UTF_8);
    } catch (IOException unreadable) {
      throw new ReplayException(Reason.UNREADABLE, path + " cannot be read: " + unreadable);
    }
  }

  private static Map<String, Object> decode(String line) {
    try {
      return Fields.map(JSON.readValue(line, OBJECT));
    } catch (JsonProcessingException notJson) {
      throw new ReplayException(Reason.MALFORMED_LOG, "a line of the log is not a JSON object");
    }
  }

  private static List<Path> logsIn(Path dir) {
    File[] files = dir.toFile().listFiles();
    if (files == null) {
      return List.of();
    }
    return Arrays.stream(files)
        .map(File::toPath)
        .filter(file -> file.getFileName().toString().endsWith(LOG_SUFFIX))
        .sorted()
        .toList();
  }

  private static String name(Path log) {
    String file = log.getFileName().toString();
    return file.substring(0, file.length() - LOG_SUFFIX.length());
  }
}
