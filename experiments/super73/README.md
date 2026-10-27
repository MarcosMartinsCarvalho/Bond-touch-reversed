# S75 / Super73 BLE map

[Full findings](../../research/notes/11_s75_bike-findings.md). Service1554, notifier155e, register write155f, register ID1564, all with suffix `-1212-efde-1523-785feabcd123`.

`02 01 LL HH …` → `u16LE(bytes[2:4])/100` km/h. Read requests write the register ID, wait100ms, then send a10-byte payload. Implemented in the Android field tester; physical bike testing remains pending.
