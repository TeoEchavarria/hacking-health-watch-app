# Workout Start Protocol (Phone ↔ Watch)

## State machine (watch side)

```
RX_START → VALIDATING → STARTED
                    ↘ REJECTED
```

- **RX_START**: Watch received `/workout/start`.
- **VALIDATING**: WorkoutService is validating inputs (routineJson or DataStore fallback with routineId match).
- **STARTED**: Timers have started or are guaranteed to start; exactly one ACK `status: "STARTED"` is sent.
- **REJECTED**: Workout will not start; exactly one ACK `status: "REJECTED"` is sent, then service stops.

Exactly one of STARTED or REJECTED must occur per `/workout/start`. ACK is sent only from WorkoutService (service-level code), never from Activity or from WorkoutMessageReceiverService after starting the service.

## 1) Command: /workout/start

**Path:** `/workout/start`

**Payload (JSON):**
```json
{
  "routineId": "<UUID string>",
  "startAt": "<ISO-8601>",
  "routineJson": "<optional string, same routine as JSON>",
  "segments": [ ... ]
}
```

- `routineId` (string, UUID): required.
- `sentAt` / `startAt` (ISO-8601): optional in payload; can be carried as `startAt` in Routine.
- `routineJson`: optional; if present, watch may use it directly; otherwise watch uses DataStore fallback keyed by routineId.

## 2) Mandatory ACK: /workout/ack

**Path:** `/workout/ack`

**Payload (JSON):**
```json
{
  "routineId": "<string>",
  "status": "STARTED" | "REJECTED",
  "reason": "<optional string, only for REJECTED>",
  "at": "<ISO-8601>"
}
```

**Rules:**
- ACK is sent only after the watch has deterministically decided:
  - **STARTED** → timers have started or are guaranteed to start.
  - **REJECTED** → workout will not start.
- ACK must never depend on UI launch success.
- ACK must never be sent from Activity; only from service-level code (WorkoutService).

## 3) Time guarantees

- Watch must send ACK within **500 ms** of the service start decision (STARTED or REJECTED).
- Phone timeout = **2 seconds**. If no ACK is received within 2 s, phone assumes **UNKNOWN** and shows an actionable error:  
  *"Watch started but did not confirm. Open watch to continue."*

## Invariants

- If WorkoutService is running with an active workout, timers MUST be running or explicitly failed (e.g. REJECTED and stopSelf).
- If status=STARTED is ACKed, a timer tick MUST already be scheduled and the log line `WorkoutService STARTED routineId=... segment=0 remaining=...` must exist.
- Phone never waits for an ACK that cannot arrive: timeout is 2 s and then phone shows the UNKNOWN message and does not block.

---

## Validation checklist (deterministic)

Use this checklist to verify protocol alignment after any change.

### 1. Send `/workout/start` from phone

From the phone app, tap "Start workout" so that `/workout/start` is sent to the watch.

### 2. Verify logs on WATCH (filter tag `WorkoutProtocol`)

- **RX start:** `RX /workout/start routineId=...`
- **Validation:** `WorkoutService validating routine routineId=...`
- **Outcome (exactly one):**
  - `WorkoutService STARTED routineId=... segment=0 remaining=...`
  - **or** `WorkoutService REJECTED routineId=... reason=...`
- **ACK sent:** `TX /workout/ack status=STARTED` or `TX /workout/ack status=REJECTED`

If any of these logs do not appear when a start is attempted, that is a bug.

### 3. Verify logs on PHONE (filter tag `WorkoutProtocolPhone`)

- **TX start:** `TX /workout/start routineId=...`
- **RX ack (if ACK received):** `RX /workout/ack status=... routineId=...`
- **Timeout (if no ACK in 2 s):** `ACK timeout routineId=...`

### 4. Verify WATCH behavior

**If STARTED:**

- Timer ticks are logged every second (WorkoutService timer).
- Ongoing notification updates (segment label and remaining time).
- Opening the watch app shows the active timer (WorkoutActivity observes service state).
- No lingering notification after workout is stopped.

**If REJECTED:**

- No WorkoutService running (service called `stopSelf()`).
- No ongoing workout notification.
- Phone shows rejection reason (if provided) or generic "Watch rejected the workout."

**If timeout (no ACK):**

- Phone shows: "Watch started but did not confirm. Open watch to continue."
- Watch may still have started the workout; user can open watch to confirm.
