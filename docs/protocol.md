```mermaid
sequenceDiagram
    participant TCP Client
    participant TCP Server
    participant UDP Client
    participant UDP Server

    TCP Client ->> TCP Server : create TCP connection
    
    TCP Client ->> TCP Server : CMD_GET_FORMAT
    TCP Server -->> TCP Client : AudioFormat (incl. server_protocol_version)

    opt server_protocol_version >= 1
        TCP Client ->> TCP Server : CMD_SET_CAPABILITIES + PlaybackCapabilities
    end

    TCP Client ->> TCP Server : CMD_START_PLAY
    TCP Server -->> TCP Client : id
    
    par
        loop every 3s
            TCP Server ->> TCP Client : CMD_HEARTBEAT
            TCP Client -->> TCP Server : CMD_HEARTBEAT
        end
    and
        UDP Client ->> UDP Server : id
        loop once have captured data
            UDP Server -->> UDP Client : PCM data
        end
    end
```

## Audio format compatibility

The server captures one shared format and broadcasts identical raw PCM to every
peer, so it cannot transcode per client. The **client** therefore performs the
authoritative format fallback: it maps the reported `AudioFormat` to a config its
`AudioTrack` can actually play (down-converting 24/32-bit PCM on API < 31,
downmixing channel layouts with no valid mask) and converts each incoming PCM
packet accordingly.

`CMD_SET_CAPABILITIES` is an **optional, backward-compatible** addition:

- `AudioFormat.server_protocol_version` (field 4) is `0`/absent on legacy
  servers and `>= 1` on servers that understand capabilities.
- Only when it is `>= 1` does the client send `CMD_SET_CAPABILITIES` followed by
  a length-prefixed `PlaybackCapabilities` (the same `cmd, size, bytes` framing
  as the format response). The server records it and logs an actionable
  diagnostic (e.g. recommending a capture `--encoding`); it does not change the
  shared stream.
- A new client talking to an old server simply never sends the command, and an
  old client never sends it either, so the main flow above is unchanged for all
  client/server version combinations.