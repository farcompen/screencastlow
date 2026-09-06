# ScreenCast v5 - Low Latency Audio + Video

- UDP discovery: 37020
- H.264 video TCP: 37021
- AAC audio TCP: 37022
- Video: 720p portrait/landscape, 30 FPS, 2.5 Mbps, 1s keyframe
- Audio: AAC-LC, 48 kHz stereo, 96 kbps
- Separate audio/video sockets and threads
- Non-blocking AudioTrack writes
- Small socket/audio buffers to prevent growing latency
- Receiver drops frames when decoder cannot accept them immediately

Both Sender and Receiver must be updated to v5 because discovery/protocol ports changed.
