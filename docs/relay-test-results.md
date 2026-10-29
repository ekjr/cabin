# Relay test results (2026-10-28)

Every test run on the Cat S22 Flip (Android 11, Qualcomm msm8952 / "pronto" Bluetooth) while trying to
get call audio through a Bluetooth headset (AirPods Pro) connected to the tablet. See
[relay-experiments.md](relay-experiments.md) for the experiment catalog, the method, and the analysis.

"Caller" is the person on the phone. "Wearer" is whoever wore the AirPods.
Nothing here is a pass: no run produced clean two-way call audio through the headset.

## Summary

| Goal | Result |
|---|---|
| Two-way call audio through the headset (second call link) | **Not working.** The caller hears clicking in every variant; the wearer hears nothing, their own voice, or sometimes the caller. |
| Caller's voice to the headset as music, using the tablet's own mic | **Not working yet.** The tablet streams it correctly, but it is not heard during a call. Outside a call the same path is clearly audible. |

## Part 1: second call link (`RELAY=call`)

All with `RELAY=call`, the AirPods connected as a headset (`HFP_AG` auto), Bluetooth restarted before most runs.

| # | Experiments | Wearer heard | Caller heard | Key evidence |
|---|---|---|---|---|
| 1 | `pin8k` (root helper down, no snapshots) | own voice | not reported | Helper had a stale pid file and never ran; fixed afterwards. |
| 2 | `pin8k` | the caller | clicks | Phone link CVSD, AirPods link mSBC, both up together. |
| 3 | `pin16k` | own voice | not reported | |
| 4 | `hs_connect` | nothing | clicks | `connectAudio` accepted; call stayed on the tablet earpiece because no route was selected. |
| 5 | `hs_connect,route_bt` | nothing | clicks | Telecom held the Bluetooth route ~26 s. Both links up ~27 s. |
| 6-7 | `hs_connect,route_bt,hfp_reassert,mixer_reapply` (2 calls) | nothing | clicks | Same as 5. |
| 8 | `sw_down,sw_up,mute_local,hs_connect,route_bt` | own voice | clicks | `sw_down` ran (caller audio played to the headset call link for ~40 s). `sw_up` failed to start: `AudioRecord` uninitialized. |
| 9 | `hs_connect,route_bt` + runtime CVSD patch | nothing | clicks | Packet log: AirPods negotiated CVSD (`+BCS: 1`), so both links CVSD. Still clicks. |

Not run: `hs_vvc`, `hs_audiomgr`, `early`, `late`, `hfp_cycle`, `nudge`, `mode_comm` (as a call-link test), `a2dp_keep` (as a call-link test).
`cvsd` (via `BluetoothHeadset.disableWBS`) cannot work on this build: the method does not exist.

### What the packet logs showed (`hci_snoop*.cfa`, read with `btmon`)

- The phone link negotiates to **CVSD**; the AirPods link negotiates to **mSBC** ("Transparent Data" air coding, 60-byte packets).
- The phone offers mSBC first and falls back to CVSD because the tablet's chip rejects the phone's mSBC link when the tablet accepts it (status `0x0e` "Security Reasons" while the AirPods link is up, `0x1f` "Unspecified Error" otherwise). The AirPods mSBC link, set up by the tablet with 13 ms latency and packet types `0x0300`, succeeds on the same chip.
- The phone's link must come up first; with the AirPods link up first the phone's link fails until the AirPods link is dropped.
- With both links on the same codec (run 9), the caller still heard clicks, so the codec mismatch is real but not the cause. Working hypothesis: the chip has one shared audio path for call links and cannot run two separately.
- Runtime patch used for run 9 (device-specific, in memory only, since reverted): `libbluetooth_qti.so` vaddr `0x137b30`, `bmi.w` (`00 f1 3d 81`) replaced by `NOP.W` (`af f3 00 80`) so the AG always picks CVSD in `AT+BAC` handling.

## Part 2: invalid runs (call audio broken by tablet state, not by an experiment)

These runs say nothing about the headset; they are recorded because they cost time and explain odd results.

| When | Symptom | Cause found |
|---|---|---|
| After the first `RELAY=media` calls | Both sides heard nothing | An orphaned `tinycap` (relay capture) kept running, holding the call's audio port. Killed; relay stop now kills the capture by name. |
| Same period | Tablet did not ring / no audio | Bluetooth app stuck in `state=AudioOn` / `mAudioState: 2` with no call (`bta_hf_client_sco_create: Index 0x0000 already in use`). `cabin restart-bt` clears it. |
| Same period | Bluetooth restarted and Gabeldorsche toggled on then off | Developer-options "Enable Gabeldorsche" was switched on by mistake for a while. Left off afterwards. |
| After a full tablet reboot | Wearer/tablet heard nothing; caller heard the tablet | Call-audio loopback never started on that call. A call after restarting the audio HAL (`cabin refresh-audio`) worked. |
| A/B of the app version | 2.2.10 and 2.3.0 both worked after the HAL restart | The app version was not the cause. |
| Several first calls after a Bluetooth restart | No or partial audio | First call after a restart has been unreliable; use a throwaway call. |

## Part 3: caller to headset as music (`RELAY=media`), tablet's own mic

Setup: `HFP_AG=off` (headset cannot open a call link), `RELAY=media` (root `tinycap` taps the phone link,
the app plays it to the headset over A2DP), experiments as listed.

| # | Config | Wearer heard | Findings |
|---|---|---|---|
| 1 | `a2dp_keep` | nothing | Audio policy phone state `IN_CALL` suspends the headset's A2DP output: output thread `Suspend count: 1`, millions of suspended frames, stream stayed `STANDBY`. App-level `A2dpSuspended=false` cannot override it. |
| 2 | no call, 440 Hz tone appended to the relay file | **the tone (clearly)** | The playback path (app, audio server, A2DP, headset) works outside a call. |
| 3 | captured audio inspected mid-call | n/a | Real speech, peaks ~12000 of 32767, but ~30 dB below the tone at the headset output. |
| 4 | `mode_comm` | nothing | Cabin sets `IN_COMMUNICATION`; Telecom sets `IN_CALL` ~70 ms later. Cannot work. (Two of these calls were also invalid, see Part 2.) |
| 5 | `voip_mode` (gain 1x) | nothing | Policy dump mid-call: `AUDIO_MODE_IN_COMMUNICATION`, output not suspended, writing. AVDTP stream started ~1 s after answering and stayed up for the call. |
| 6 | `voip_mode`, 8x gain | nothing | Same; level at the headset still far too low. |
| 7 | `voip_mode`, 24x gain + limiter | "nothing until after I hung up, then something" | Stream active during the call; no audible output until the call ended. |
| 8 | `voip_mode`, 440 Hz tone injected mid-call (11 s and 18 s in) | **no beep, during or after** | Tablet side healthy at the time of injection: mode `IN_COMMUNICATION`, suspend count 0, writes every ~200 ms, no errors in the Bluetooth audio logs. The sound is lost downstream of the tablet's audio server. |

**Conclusion so far:** the tablet delivers the caller's audio to the headset's A2DP output correctly, but it is
not heard while the phone's call link is open. Suspects: the Bluetooth stack/controller throttling A2DP during
an active eSCO link, or the AirPods. Not yet tested: a different Bluetooth speaker or headphones (same tone test).

## Bugs fixed while testing

- `service.sh`/`cabin status`/`uninstall.sh` trusted a stale pid in `cabind.pid`, so after a reboot the root helper was not started and app-to-root requests queued forever.
- Media relay: not stopped when the call ended (the phone's audio link can report closed 20+ s late) and restarted by a late "audio connected" report after hang-up, leaving an orphaned `tinycap`.
- Experiment snapshot jobs delayed the relay's start by 4-5 s in the root helper queue.
- `mode_comm` restored a stale audio mode after the call, leaving the audio policy in `IN_CALL`.

## Test environment notes

- Module 2.3.0 plus the experiment builds from this branch, installed over the tablet's existing app with `pm install -r` (same signing key).
- The snoop log is `/data/misc/bluetooth/logs/hci_snoop<time>.cfa` (btsnoop format), not `btsnoop_hci.log`; it does not record outgoing A2DP media packets on this device.
- All times are 2026-10-28 into 2026-10-29, device local time.
