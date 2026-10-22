package net.robbrown.actortx.api;

import java.util.List;
import net.robbrown.actortx.service.BusyException;
import net.robbrown.actortx.service.InvalidInputException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns bad input into the same error shape the whole API uses: {@code {ok: false, error}}. */
@RestControllerAdvice
public class ApiExceptionHandler {

  /** An error body. */
  public record ApiError(boolean ok, String error, List<String> errors) {}

  /** The input was understood but not valid. */
  @ExceptionHandler(InvalidInputException.class)
  public ResponseEntity<ApiError> invalid(InvalidInputException exception) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(new ApiError(false, exception.getMessage(), exception.errors()));
  }

  /** The system is at its admission limit. */
  @ExceptionHandler(BusyException.class)
  public ResponseEntity<ApiError> busy(BusyException exception) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", "1")
        .body(new ApiError(false, exception.getMessage(), List.of(exception.getMessage())));
  }

  /** The body was not the JSON that was expected. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException exception) {
    return ResponseEntity.badRequest()
        .body(new ApiError(false, "expected a JSON object", List.of("expected a JSON object")));
  }
}
