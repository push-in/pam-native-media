<?php

declare(strict_types=1);

namespace Pam\Native\Media;

use Closure;
use JsonException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/** A running transcode; progress is long-polled natively without blocking PHP. */
final class TranscodeTask
{
    private const string MODULE = 'media';

    private ?int $task = null;

    private bool $finished = false;

    private bool $cancelRequested = false;

    private function __construct(
        private readonly Closure $then,
        private readonly ?Closure $failed,
        private readonly ?Closure $progress,
    ) {
    }

    /**
     * @internal
     * @param array<string, int|bool> $options
     */
    public static function start(string $source, string $destination, array $options, Closure $then, ?Closure $failed, ?Closure $progress): self
    {
        $task = new self($then, $failed, $progress);
        try {
            $encoded = json_encode($options, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new \InvalidArgumentException($error->getMessage(), previous: $error);
        }
        NativeModules::call(self::MODULE, 'transcodeStart', ['source' => $source, 'destination' => $destination, 'options' => $encoded], static function (NativeModuleResult $result) use ($task): void {
            if (!$result->succeeded()) {
                $task->fail($result->message());

                return;
            }
            $task->task = (int) ($result->values()['task'] ?? 0);
            if ($task->cancelRequested) {
                $task->cancel();

                return;
            }
            $task->next();
        });

        return $task;
    }

    public function cancel(): void
    {
        if ($this->finished) {
            return;
        }
        $this->cancelRequested = true;
        if ($this->task === null) {
            return;
        }
        NativeModules::call(self::MODULE, 'transcodeCancel', ['task' => $this->task], static fn (): null => null);
    }

    public function finished(): bool
    {
        return $this->finished;
    }

    private function next(): void
    {
        NativeModules::call(self::MODULE, 'transcodeNext', ['task' => (int) $this->task], function (NativeModuleResult $result): void {
            if ($this->finished) {
                return;
            }
            if (!$result->succeeded()) {
                $this->fail($result->message());

                return;
            }
            $values = $result->values();
            switch ((int) ($values['state'] ?? 3)) {
                case 1:
                    $this->progress?->__invoke(max(0.0, min(1.0, (float) ($values['progress'] ?? 0.0))));
                    $this->next();

                    return;
                case 2:
                    $this->finished = true;
                    ($this->then)(new TranscodeResult(
                        (string) ($values['path'] ?? ''),
                        (string) ($values['mimeType'] ?? 'video/mp4'),
                        (int) ($values['width'] ?? 0),
                        (int) ($values['height'] ?? 0),
                        (int) ($values['durationMillis'] ?? 0),
                        (int) ($values['bytes'] ?? 0),
                        (int) ($values['bitrate'] ?? 0),
                        (bool) ($values['fastStart'] ?? false),
                    ));

                    return;
                default:
                    $this->fail((string) ($values['message'] ?? 'Transcode failed.'));
            }
        });
    }

    private function fail(string $message): void
    {
        $this->finished = true;
        $this->failed?->__invoke($message);
    }
}
