# Runbook: DeadLetterRate (> 1 event/s into nexus.gps.dlq for 10 min)

**Meaning:** the writer is isolating rows the database rejects. Ingest validation should have caught them, so the usual
causes are a producer bypassing ingest, a schema or constraint change, or a validation bug.

1. Inspect (dry run lists every record with its error class, SQLState and message):
   ```sh
   docker compose exec writer java -cp /app/nexus-writer.jar \
     -Dloader.main=dev.nexus.writer.DlqReplay \
     org.springframework.boot.loader.launch.PropertiesLauncher
   # prints e.g.: seen=0 republished=0 still-invalid=0 (dry run: pass --republish to send)
   ```
2. Group by SQLState: `23514` check violation (out-of-range values), `22021` invalid bytes, `MALFORMED` not a ping.
3. Fix the cause (validation rule or producer), then replay by appending `--republish` (and `--fix-heading` if it
   applies). Replaying is safe to repeat: storage dedups on `(vehicle_id, event_ts, event_id)`.
