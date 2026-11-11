Institutional mark
==================

logo.png is shown in the application header, left of the title, scaled to
34px tall. Remove the file and the header lays out without it. Replace it and
the new mark is picked up on the next build. Nothing else needs changing; see
Theme.logo.

The file currently here is the Stony Brook University shield, supplied by the
author and trimmed of its transparent margin. It has not been recoloured,
cropped into, or redrawn — altering an institutional mark is usually the
first thing brand guidelines prohibit. The source was 200px square, so
icon-256.png is upscaled and slightly soft; a vector original (SVG or EPS)
from the university's brand portal would regenerate the whole set crisply.

Before you add one, two things are worth being deliberate about.

Permission. An institutional mark usually has brand guidelines governing who
may apply it to what. Student coursework is not automatically covered. Check
before shipping, especially if the project is going to be published, demoed
publicly, or put in a portfolio.

Meaning. This application reads CPIC guideline data and is explicitly not
validated for patient care. A health system's mark in the header of a tool
that produces drug recommendations reads as institutional endorsement of
those recommendations, whatever the disclaimer in the status bar says. That
is a stronger claim than "a student here wrote this", which is what the
attribution line in the status bar already states.

If what you want is to show where the work came from, the attribution line
does that honestly. If what you want is a poster or a title slide, the mark
belongs there instead, where nothing is being computed.


Window and taskbar icon
=======================

icon-16.png … icon-256.png are the window and taskbar icon, loaded by
Theme.appIcons and handed to the Stage. The operating system picks the
nearest size, so replacing only the large one gives you a blurry taskbar.
Replace the whole set, keep the names, rebuild.

The mark shipped here is original: "*/*", which is how every diplotype in
this project is written. At 32px and below it drops to a single asterisk,
because three glyphs are not legible at that size.

The same two questions in the section above apply if you replace it with an
institutional mark — more so, since a taskbar icon travels further than a
header does and carries no disclaimer with it.
