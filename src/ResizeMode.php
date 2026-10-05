<?php

declare(strict_types=1);

namespace Pam\Native\Media;

enum ResizeMode: int
{
    /** Fit inside the box, preserving the aspect ratio. */
    case Contain = 1;
    /** Fill the box, preserving the aspect ratio, and center-crop the overflow. */
    case Cover = 2;
    /** Use exactly the requested dimensions. */
    case Stretch = 3;
}
