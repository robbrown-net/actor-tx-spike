package net.robbrown.actortx.api;

import java.time.Duration;
import java.util.Map;
import net.robbrown.actortx.service.PaymentCommand;
import net.robbrown.actortx.service.PaymentService;
import net.robbrown.actortx.service.PaymentView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Send a payment, look at it, wait for it to end. */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

  private final PaymentService payments;

  /** Fronts the payment service. */
  public PaymentController(PaymentService payments) {
    this.payments = payments;
  }

  /** Start a payment; it runs on, and is looked at by id. */
  @PostMapping
  public ResponseEntity<Map<String, Object>> send(@RequestBody PaymentCommand command) {
    String id = payments.submit(command);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("ok", true, "id", id));
  }

  /** The transaction as it stands. */
  @GetMapping("/{id}")
  public ResponseEntity<PaymentView> view(@PathVariable String id) {
    return payments
        .view(id)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** Wait (up to {@code timeout_ms}) for the transaction to end; 408 if it does not. */
  @GetMapping("/{id}/result")
  public ResponseEntity<PaymentView> result(
      @PathVariable String id,
      @RequestParam(name = "timeout_ms", defaultValue = "5000") long timeoutMs) {
    if (payments.view(id).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return payments
        .await(id, Duration.ofMillis(timeoutMs))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).build());
  }
}
