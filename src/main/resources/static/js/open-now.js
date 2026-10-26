/**
 * «Aperto ora / Chiuso ora» next to the office hours (ENH-18, ADR 0042). The server writes the
 * openings of the next days (epoch milliseconds, Europe/Rome hours and closure days already
 * applied) in data-intervals; this module compares them with the clock of the browser, at load
 * and every minute, so a page kept in a cache never shows a stale state. Without JavaScript the
 * badge stays hidden and the hours are still there.
 */
const badges = [...document.querySelectorAll('[data-open-now]')];

function update() {
  const now = Date.now();
  for (const badge of badges) {
    let intervals = [];
    try {
      intervals = JSON.parse(badge.dataset.intervals || '[]');
    } catch {
      intervals = [];
    }
    const open = intervals.some(([start, end]) => start <= now && now < end);
    badge.textContent = open ? badge.dataset.openLabel : badge.dataset.closedLabel;
    badge.classList.toggle('is-open', open);
    badge.hidden = false;
  }
}

if (badges.length) {
  update();
  setInterval(update, 60_000);
}
