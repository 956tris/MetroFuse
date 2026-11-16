# Audio source format fixtures

`flac-24bit-96000.flac` is a 125 ms synthetic 997 Hz sine wave, encoded as stereo 24-bit FLAC at 96,000 Hz. `audio-quality-with-video.webm` is a 300 ms synthetic navy frame with a 997 Hz sine wave encoded as 48,000 Hz Opus. Both were generated with FFmpeg 8.1 for `AudioSourceFormatTest`.

The FLAC is opened under a `.bin` filename to verify header-based detection and the exact container average bitrate. The WebM verifies that an unknown audio bitrate is not replaced by the combined video/container bitrate. Additional PCM WAV fixtures are constructed in the test and verify the original bit depth before 16-bit output conversion and the nonstandard 44,056 Hz sample rate.

Regenerate from this directory:

```sh
ffmpeg -f lavfi -i 'sine=frequency=997:sample_rate=96000:duration=0.125' -ac 2 -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 -metadata_header_padding 0 flac-24bit-96000.flac
ffmpeg -f lavfi -i 'color=c=navy:size=64x64:rate=10:duration=0.3' -f lavfi -i 'sine=frequency=997:sample_rate=48000:duration=0.3' -c:v libvpx -deadline realtime -cpu-used 8 -c:a libopus -b:a 64k -shortest audio-quality-with-video.webm
```
