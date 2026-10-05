<?php

declare(strict_types=1);

namespace Pam\Native\Media;

use Closure;
use InvalidArgumentException;
use LogicException;

/**
 * Fluent video transcode (Media3 Transformer on Android). The source stays
 * untouched; the output is an upload-ready MP4 written atomically.
 */
final class PendingTranscode
{
    private ?string $destination = null;

    private VideoPreset $preset = VideoPreset::Adaptive;

    private int $maxBitrate = 0;

    private bool $fastStart = true;

    private bool $audio = true;

    private ?Closure $progress = null;

    /** @internal */
    public function __construct(private readonly string $source)
    {
        MediaPath::assert($source);
    }

    public function to(string $destination): self
    {
        MediaPath::assert($destination);
        if ($destination === $this->source) {
            throw new InvalidArgumentException('Transcode destination must differ from the source.');
        }
        $this->destination = $destination;

        return $this;
    }

    public function preset(VideoPreset $preset): self
    {
        $this->preset = $preset;

        return $this;
    }

    /** Caps the video bitrate (bits per second) below the preset target. */
    public function maxBitrate(int $bitsPerSecond): self
    {
        if ($bitsPerSecond < 100_000 || $bitsPerSecond > 50_000_000) {
            throw new InvalidArgumentException('Maximum bitrate must be between 100 kbps and 50 Mbps.');
        }
        $this->maxBitrate = $bitsPerSecond;

        return $this;
    }

    /** Moves the `moov` atom before `mdat` so players and CDNs can stream progressively (default on). */
    public function fastStart(bool $enabled = true): self
    {
        $this->fastStart = $enabled;

        return $this;
    }

    public function withoutAudio(): self
    {
        $this->audio = false;

        return $this;
    }

    /** @param Closure(float): void $listener receives 0.0-1.0 */
    public function progress(Closure $listener): self
    {
        $this->progress = $listener;

        return $this;
    }

    /** @internal @return array{preset: int, maxBitrate: int, fastStart: bool, audio: bool} */
    public function options(): array
    {
        return ['preset' => $this->preset->value, 'maxBitrate' => $this->maxBitrate, 'fastStart' => $this->fastStart, 'audio' => $this->audio];
    }

    /**
     * @param Closure(TranscodeResult): void $then
     * @param null|Closure(string): void $failed
     */
    public function run(Closure $then, ?Closure $failed = null): TranscodeTask
    {
        if ($this->destination === null) {
            throw new LogicException('Call to($destination) before run().');
        }

        return TranscodeTask::start($this->source, $this->destination, $this->options(), $then, $failed, $this->progress);
    }
}
