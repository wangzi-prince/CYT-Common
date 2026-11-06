package group.cyt.common.module.adapter.wecom.approval;

import org.springframework.web.bind.annotation.*;

@RestController
public class WecomApprovalController {
  private final WecomApprovalEvents events;

  public WecomApprovalController(WecomApprovalEvents events) {
    this.events = events;
  }

  @GetMapping(
      value = "/integrations/wecom/webhooks/{system}/events",
      produces = "text/plain;charset=UTF-8")
  public String verify(
      @PathVariable String system,
      @RequestParam String msg_signature,
      @RequestParam String timestamp,
      @RequestParam String nonce,
      @RequestParam String echostr) {
    return events.decrypt(system, msg_signature, timestamp, nonce, echostr);
  }

  @PostMapping(
      value = "/integrations/wecom/webhooks/{system}/events",
      produces = "text/plain;charset=UTF-8")
  public String receive(
      @PathVariable String system,
      @RequestParam String msg_signature,
      @RequestParam String timestamp,
      @RequestParam String nonce,
      @RequestBody byte[] xml) {
    events.receive(system, msg_signature, timestamp, nonce, xml);
    return "success";
  }
}
