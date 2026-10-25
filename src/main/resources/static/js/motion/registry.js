/**
 * Home motion — the site's effects (ADR 0030; alpacode-motion D14 registry.ts), built in this
 * order: the document order of their first section (no pinned scene on this page).
 */
import * as count from './effects/count.js';
import * as kicker from './effects/kicker.js';
import * as rail from './effects/rail.js';
import * as reveal from './effects/reveal.js';
import * as scrub from './effects/scrub.js';
import * as splitLines from './effects/split-lines.js';

export const effects = [count, kicker, splitLines, reveal, rail, scrub];
